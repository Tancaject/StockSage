"""One direct provider stream from a saved public-source replay; no retries."""
import argparse
import hashlib
import http.client
import json
from pathlib import Path
import sys
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "evals"))
from run_ragas_eval import resolve_api_key


def delta_counts(event):
    choices = event.get("choices", [])
    delta = choices[0].get("delta", {}) if choices else {}
    return len(delta.get("reasoning_content") or ""), delta.get("content") or ""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--stage", choices=["analysis", "finalAnswer"])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        assert delta_counts({"choices": [{"delta": {"reasoning_content": "abc"}}]}) == (3, "")
        assert delta_counts({"choices": [{"delta": {"content": "正文"}}]}) == (0, "正文")
        assert delta_counts({"choices": [], "usage": {"total_tokens": 1}}) == (0, "")
        print("PASS: reasoning is counted separately from answer and usage-only chunks.")
        return
    if not args.source or not args.stage or not args.output:
        parser.error("--source, --stage and --output are required")
    raw = args.source.read_bytes()
    source = json.loads(raw)
    stage = source[args.stage]
    assert stage and stage["messages"], "Saved stage input is required"
    args.output.mkdir(parents=True, exist_ok=False)
    request = {"model": "qwen3.8-max", "temperature": 0.7, "max_tokens": 4096,
               "enable_thinking": True, "thinking_budget": 32768, "stream": True,
               "stream_options": {"include_usage": True},
               "messages": [{"role": m["role"], "content": m["text"]} for m in stage["messages"]]}
    body = json.dumps(request, ensure_ascii=False).encode("utf-8")
    (args.output / "request.json").write_bytes(body)
    plan = {"schema": "provider_stream_diagnostic_v1", "registeredAt": datetime.now(timezone.utc).isoformat(),
            "source": str(args.source), "sourceSha256": hashlib.sha256(raw).hexdigest(),
            "stage": args.stage, "requestSha256": hashlib.sha256(body).hexdigest(),
            "transport": "PYTHON_STDLIB_HTTPS_DIRECT_NO_PROXY", "maxCalls": 1, "retries": 0,
            "deadlineSeconds": 300, "qualityAssessed": False,
            "limits": "Transport diagnostic; sampling and execution time differ from the original Java call."}
    (args.output / "plan.json").write_text(json.dumps(plan, indent=2), encoding="utf-8")
    key = resolve_api_key()
    started = time.monotonic()
    elapsed = lambda: round(time.monotonic() - started, 3)
    result = {"status": "STARTED", "startedAt": datetime.now(timezone.utc).isoformat(),
              "headersSeconds": None, "firstEventSeconds": None, "firstReasoningSeconds": None,
              "firstAnswerSeconds": None, "lastEventSeconds": None, "maxEventGapSeconds": 0,
              "events": 0, "reasoningChars": 0, "answerChars": 0, "usage": None,
              "finishReason": None, "doneMarker": False}
    answer = []
    conn = http.client.HTTPSConnection("dashscope.aliyuncs.com", timeout=300)
    try:
        conn.request("POST", "/compatible-mode/v1/chat/completions", body,
                     {"Content-Type": "application/json", "Authorization": "Bearer " + key})
        response = conn.getresponse()
        result["httpStatus"] = response.status
        result["headersSeconds"] = elapsed()
        allowed = {"content-type", "server", "connection", "transfer-encoding", "x-request-id",
                   "request-id", "x-dashscope-request-id"}
        result["responseHeaders"] = {k.lower(): v for k, v in response.getheaders() if k.lower() in allowed}
        print(json.dumps({"headersSeconds": elapsed(), "httpStatus": response.status}), flush=True)
        if response.status != 200:
            result["status"] = "HTTP_ERROR"
            return
        last_progress = 0
        while True:
            remaining = 300 - (time.monotonic() - started)
            if remaining <= 0:
                raise TimeoutError("Whole-call deadline reached")
            if conn.sock:
                conn.sock.settimeout(remaining)
            line = response.readline()
            if not line:
                result["status"] = "EOF_WITHOUT_DONE"
                break
            if not line.startswith(b"data:"):
                continue
            data = line[5:].strip()
            if data == b"[DONE]":
                result["doneMarker"] = True
                result["status"] = "COMPLETED"
                break
            event = json.loads(data)
            now = elapsed()
            prior = result["lastEventSeconds"]
            result["maxEventGapSeconds"] = max(result["maxEventGapSeconds"], now - (prior or 0))
            result["firstEventSeconds"] = result["firstEventSeconds"] if prior is not None else now
            result["lastEventSeconds"] = now
            result["events"] += 1
            reasoning, text = delta_counts(event)
            if reasoning and result["firstReasoningSeconds"] is None:
                result["firstReasoningSeconds"] = now
            if text and result["firstAnswerSeconds"] is None:
                result["firstAnswerSeconds"] = now
            result["reasoningChars"] += reasoning
            result["answerChars"] += len(text)
            answer.append(text)
            if event.get("id"):
                result["responseId"] = event["id"]
            if event.get("usage"):
                result["usage"] = event["usage"]
            choices = event.get("choices", [])
            if choices and choices[0].get("finish_reason"):
                result["finishReason"] = choices[0]["finish_reason"]
            if now - last_progress >= 30:
                print(json.dumps({k: result[k] for k in ("lastEventSeconds", "events", "reasoningChars", "answerChars")}), flush=True)
                last_progress = now
    except Exception as error:
        result["status"] = "FAILED"
        result["errorType"] = type(error).__name__
        result["error"] = str(error).replace(key, "[REDACTED]")[:500]
    finally:
        conn.close()
        result["durationSeconds"] = elapsed()
        (args.output / "answer.txt").write_text("".join(answer), encoding="utf-8")
        (args.output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
