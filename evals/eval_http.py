"""Shared HTTP, authentication and SSE transport for live evaluation runners."""

from __future__ import annotations

import http.cookiejar
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable, Iterable


class LiveEvalError(RuntimeError):
    """An infrastructure or protocol error that blocks a live evaluation."""


class LiveEvalStreamError(LiveEvalError):
    """An SSE stream failed after zero or more complete events were received."""

    def __init__(self, message: str, events: Iterable[dict[str, Any]] = ()):
        super().__init__(message)
        self.events = list(events)


class StockSageClient:
    def __init__(self, base_url: str, timeout_seconds: int = 30):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        timeout_seconds: int | None = None,
        accept: str = "application/json",
    ) -> tuple[int, bytes]:
        headers = {"Accept": accept}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if method.upper() not in {"GET", "HEAD", "OPTIONS"}:
            token = self.csrf_cookie()
            if token:
                headers["X-XSRF-TOKEN"] = token
        request = urllib.request.Request(
            self.base_url + path,
            data=payload,
            headers=headers,
            method=method.upper(),
        )
        try:
            with self.opener.open(
                request, timeout=timeout_seconds or self.timeout_seconds
            ) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            raise LiveEvalError(
                f"{method.upper()} {path} returned HTTP {error.code}: {detail}"
            ) from error
        except urllib.error.URLError as error:
            raise LiveEvalError(
                f"{method.upper()} {path} failed: {error.reason}"
            ) from error

    def json_request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        timeout_seconds: int | None = None,
    ) -> tuple[int, Any]:
        status, raw = self.request(method, path, body, timeout_seconds)
        if not raw:
            return status, None
        try:
            return status, json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as error:
            raise LiveEvalError(f"{method.upper()} {path} returned invalid JSON") from error

    def csrf_cookie(self) -> str | None:
        for cookie in self.cookies:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return None

    def login(self, email: str, password: str) -> dict[str, Any]:
        self.json_request("GET", "/api/auth/csrf")
        _, user = self.json_request(
            "POST", "/api/auth/login", {"email": email, "password": password}
        )
        self.json_request("GET", "/api/auth/csrf")
        if not isinstance(user, dict) or not user.get("userId"):
            raise LiveEvalError("login response did not contain a user id")
        return {"userId": user["userId"], "email": user.get("email")}

    def stream_json(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None,
        timeout_seconds: int,
        stop_when: Callable[[dict[str, Any]], bool] | None = None,
        deadline: float | None = None,
        on_event: Callable[[dict[str, Any]], None] | None = None,
    ) -> list[dict[str, Any]]:
        headers = {"Accept": "text/event-stream"}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if method.upper() not in {"GET", "HEAD", "OPTIONS"}:
            token = self.csrf_cookie()
            if token:
                headers["X-XSRF-TOKEN"] = token
        request = urllib.request.Request(
            self.base_url + path,
            data=payload,
            headers=headers,
            method=method.upper(),
        )
        events: list[dict[str, Any]] = []
        data_lines: list[str] = []

        def record_event(event: dict[str, Any]) -> None:
            events.append(event)
            if on_event:
                on_event(event)

        try:
            with self.opener.open(request, timeout=timeout_seconds) as response:
                while True:
                    if deadline is not None and time.monotonic() >= deadline:
                        break
                    raw_line = response.readline()
                    if not raw_line:
                        if data_lines:
                            event = parse_sse_data(data_lines)
                            if event is not None:
                                record_event(event)
                        break
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    if line == "":
                        if data_lines:
                            event = parse_sse_data(data_lines)
                            data_lines = []
                            if event is not None:
                                record_event(event)
                                if stop_when and stop_when(event):
                                    break
                        continue
                    if line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            raise LiveEvalStreamError(
                f"{method.upper()} {path} returned HTTP {error.code}: {detail}",
                events,
            ) from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if data_lines:
                event = parse_sse_data(data_lines)
                if event is not None:
                    record_event(event)
            raise LiveEvalStreamError(
                f"{method.upper()} {path} stream failed: {error}",
                events,
            ) from error
        return events


def parse_sse_data(data_lines: Iterable[str]) -> dict[str, Any] | None:
    payload = "\n".join(data_lines).strip()
    if not payload or payload == "[DONE]":
        return None
    try:
        parsed = json.loads(payload)
    except json.JSONDecodeError:
        return {"type": "unparsed", "metadata": {"size": len(payload)}}
    return parsed if isinstance(parsed, dict) else None
