"""Reuse the batch controller with the new JAR's production defaults."""
import importlib.util
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

HERE = Path(__file__).resolve().parent
source = HERE.parent / "batch-learning-20261002/experiment.py"
spec = importlib.util.spec_from_file_location("batch_learning_validation_runner", source)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
runner.HERE = HERE
runner.BUILD = runner.REPO / "stocksage-backend/target/evolution-build-batch-learning-validation-20261002"
runner.DRIVER = runner.REPO / "stocksage-backend/target/evolution-batch-repair-20261002-driver"
runner.EXPERIMENT = "fundamentals-batch-learning-validation-20261002"

LEGACY_OVERRIDES = {
    "-Dspring.http.client.read-timeout=180s",
    "-Dstocksage.chat.model-routing.standard-model=qwen3.8-max",
    "-Dspring.ai.openai.chat.options.temperature=0.7",
    "-Dspring.ai.openai.chat.options.max-tokens=4096",
    "-Dstocksage.agent.prefetch.timeout-seconds=180",
}


def java_with_production_defaults(args, **kwargs):
    # Keep the frozen driver's invocation intact while removing its old experiment overrides.
    if args[0] != "java" or any(args.count(option) != 1 for option in LEGACY_OVERRIDES):
        raise ValueError("Expected the frozen batch runner's five legacy Java options")
    return subprocess.run([arg for arg in args if arg not in LEGACY_OVERRIDES], **kwargs)


runner.subprocess = SimpleNamespace(run=java_with_production_defaults, STDOUT=subprocess.STDOUT)

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in {"register", "run", "collect", "learn"}:
        raise SystemExit("Use register, run, collect or learn; the runtime manifest must bind the new JAR and frozen repair driver.")
    raise SystemExit(runner.main())
