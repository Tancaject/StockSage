"""HTTP 契约共用的日期校验：只接受 date 对象或严格的 YYYY-MM-DD 字符串。"""

from datetime import date as Date


def business_date(value, message: str, *, allow_none: bool = True):
    if (value is None and allow_none) or type(value) is Date:
        return value
    if isinstance(value, str):
        parsed = Date.fromisoformat(value)
        if parsed.isoformat() == value:
            return parsed
    raise ValueError(message)
