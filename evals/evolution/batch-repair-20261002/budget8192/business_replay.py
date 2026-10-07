"""Run the registered repair cases with only the thinking budget changed to 8192."""
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
runner.EXPERIMENT = "fundamentals-batch-repair-budget8192-20261002"


def java_with_budget(args, **kwargs):
    if args[0] != "java" or any(arg.startswith("-Dstocksage.chat.model-routing.standard-thinking-budget=") for arg in args):
        raise ValueError("Expected the unchanged Java runner command without a thinking-budget override")
    return subprocess.run([args[0], "-Dstocksage.chat.model-routing.standard-thinking-budget=8192", *args[1:]], **kwargs)


runner.subprocess = SimpleNamespace(run=java_with_budget, STDOUT=subprocess.STDOUT)

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in {"register", "run", "collect"}:
        raise SystemExit("Use register, run or collect; the parent repair runtime remains frozen.")
    raise SystemExit(runner.main())
