"""Use the existing frozen-component runner with a separate repair registration."""
import importlib.util
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
source = HERE.parent / "batch-learning-20261002/experiment.py"
spec = importlib.util.spec_from_file_location("batch_repair_runner", source)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
runner.HERE = HERE
runner.BUILD = runner.REPO / "stocksage-backend/target/evolution-build-batch-repair-20261002"
runner.DRIVER = runner.REPO / "stocksage-backend/target/evolution-batch-repair-20261002-driver"
runner.EXPERIMENT = "fundamentals-batch-repair-20261002"

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in {"prepare-runtime", "register", "run", "collect"}:
        raise SystemExit("Use prepare-runtime, register, run or collect for this repair comparison.")
    raise SystemExit(runner.main())
