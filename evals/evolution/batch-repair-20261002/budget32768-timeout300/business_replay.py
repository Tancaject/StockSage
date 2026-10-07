"""Use the frozen repair runner with xhigh thinking and a separately registered 300s deadline."""
import importlib.util
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("parent_repair_runner", HERE.parent / "business_replay.py")
parent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parent)
runner = parent.runner
runner.HERE = HERE
runner.EXPERIMENT = "fundamentals-batch-repair-budget32768-timeout300-20261002"


def java_with_budget(args, **kwargs):
    replacements = {"-Dspring.http.client.read-timeout=180s": "-Dspring.http.client.read-timeout=300s",
                    "-Dstocksage.agent.prefetch.timeout-seconds=180": "-Dstocksage.agent.prefetch.timeout-seconds=300"}
    if args[0] != "java" or any(args.count(key) != 1 for key in replacements):
        raise ValueError("Expected the frozen Java runner's two 180-second options")
    if any(arg.startswith("-Dstocksage.chat.model-routing.standard-thinking-budget=") for arg in args):
        raise ValueError("Unexpected existing thinking-budget override")
    return subprocess.run([args[0], "-Dstocksage.chat.model-routing.standard-thinking-budget=32768",
                           *[replacements.get(arg, arg) for arg in args[1:]]], **kwargs)


runner.subprocess = SimpleNamespace(run=java_with_budget, STDOUT=subprocess.STDOUT)

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in {"register", "run", "collect"}:
        raise SystemExit("Use register, run or collect; phase two requires the frozen plan's independent quality decision.")
    raise SystemExit(runner.main())
