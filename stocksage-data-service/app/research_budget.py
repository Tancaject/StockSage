"""Request-scoped cooperative deadline on this host's monotonic clock."""
from contextvars import ContextVar
import re
import time
from starlette.responses import JSONResponse

_deadline = ContextVar("research_deadline", default=None)


class ResearchBudgetExceeded(RuntimeError):
    pass


def check_budget(error=None):
    if isinstance(error, ResearchBudgetExceeded):
        raise error
    deadline = _deadline.get()
    if deadline is not None and time.monotonic() >= deadline:
        raise ResearchBudgetExceeded("Research request budget expired in data-service; start a new research run.")


def bounded_timeout(seconds):
    deadline = _deadline.get()
    if deadline is None:
        return seconds
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ResearchBudgetExceeded("Research request budget expired in data-service; start a new research run.")
    return min(seconds, remaining)


def budget_call(fn, *args, **kwargs):
    check_budget()
    try:
        result = fn(*args, **kwargs)
    except Exception as error:
        check_budget(error)
        raise
    check_budget()
    return result


class ResearchBudgetMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        values = [v for k, v in scope["headers"] if k.lower() == b"x-stocksage-remaining-ms"]
        if values and (len(values) != 1 or not re.fullmatch(rb"[0-9]{1,7}", values[0])
                       or not 1 <= int(values[0]) <= 1800000):
            return await JSONResponse({"code": "INVALID_RESEARCH_BUDGET", "message": "X-StockSage-Remaining-Ms must be an integer from 1 to 1800000."}, status_code=400)(scope, receive, send)
        token = _deadline.set(time.monotonic() + int(values[0]) / 1000 if values else None)
        started = False

        async def budget_send(message):
            nonlocal started
            if message["type"] == "http.response.start":
                check_budget()
                started = True
            await send(message)

        try:
            await self.app(scope, receive, budget_send)
        except ResearchBudgetExceeded as error:
            if started:
                raise
            await JSONResponse({"code": "RESEARCH_BUDGET_EXCEEDED", "message": str(error)}, status_code=504)(scope, receive, send)
        finally:
            _deadline.reset(token)
