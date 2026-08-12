"""Pure technical-indicator calculations shared by market-data providers."""

from collections.abc import Callable, Collection
from typing import Any

import pandas as pd


RoundValue = Callable[[Any, int], Any]


def calculate_technical_indicators(
    close: pd.Series,
    indicators: Collection[str],
    round_value: RoundValue = round,
) -> dict[str, Any]:
    """Calculate the requested indicators without fetching or shaping provider data."""
    result: dict[str, Any] = {}

    if "MA" in indicators:
        for window in (5, 10, 20, 60):
            result[f"MA{window}"] = round_value(
                close.rolling(window).mean().iloc[-1],
                2,
            )

    if "RSI" in indicators:
        delta = close.diff()
        gain = delta.where(delta > 0, 0).rolling(14).mean()
        loss = (-delta.where(delta < 0, 0)).rolling(14).mean()
        rs = gain / loss
        result["RSI14"] = round_value(100 - 100 / (1 + rs.iloc[-1]), 2)

    if "MACD" in indicators:
        ema12 = close.ewm(span=12).mean()
        ema26 = close.ewm(span=26).mean()
        dif = ema12 - ema26
        dea = dif.ewm(span=9).mean()
        macd = 2 * (dif - dea)
        result["MACD_DIF"] = round_value(dif.iloc[-1], 4)
        result["MACD_DEA"] = round_value(dea.iloc[-1], 4)
        result["MACD"] = round_value(macd.iloc[-1], 4)

    return result
