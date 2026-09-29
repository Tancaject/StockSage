"""Build an evaluation JAR from a frozen, Git-visible source snapshot without local secrets."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from ordinary_answer_quality import json_hash


def source_snapshot(repository: Path) -> tuple[str, dict[str, bytes]]:
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
    names = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "stocksage-backend"], cwd=repository)
    files = {}
    backend = (repository / "stocksage-backend").resolve()
    for name in sorted(set(names.decode("utf-8").split("\0")) - {""}):
        relative = Path(name).relative_to("stocksage-backend")
        if not (relative.parts[0] in {"src", ".mvn"} or relative.as_posix() in {"pom.xml", "mvnw", "mvnw.cmd"}):
            continue
        # Local profiles are never a distributable build input, even if accidentally added to Git.
        if relative.name.startswith("application-local.") or relative.name.startswith(".env"):
            continue
        source = (backend / relative).resolve()
        if not source.is_relative_to(backend):
            raise ValueError("Build source resolves outside the backend module: " + relative.as_posix())
        if source.is_file():
            files[relative.as_posix()] = source.read_bytes()
    if "pom.xml" not in files or not any(name.endswith(".java") for name in files):
        raise ValueError("No buildable backend source snapshot found")
    wrapper_name = ".mvn/wrapper/maven-wrapper.jar"
    properties = files.get(".mvn/wrapper/maven-wrapper.properties", b"").decode("utf-8")
    expected = next((line.split("=", 1)[1].strip() for line in properties.splitlines() if line.startswith("wrapperSha256Sum=")), None)
    cached_wrapper = (backend / wrapper_name).read_bytes()
    if expected is None or hashlib.sha256(cached_wrapper).hexdigest() != expected:
        raise ValueError("Cached Maven wrapper does not match the versioned checksum; fix the local toolchain before building")
    files[wrapper_name] = cached_wrapper
    return revision, files


def build(repository: Path, output: Path) -> dict:
    repository, output = repository.resolve(), output.resolve()
    revision, files = source_snapshot(repository)
    output.mkdir(parents=True, exist_ok=False)
    scratch_root = (repository / "stocksage-backend" / "target").resolve()
    if not scratch_root.is_relative_to(repository):
        raise ValueError("Build scratch directory must stay inside the repository")
    scratch_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="evolution-build-", dir=scratch_root) as temporary:
        scratch = Path(temporary).resolve()
        if not scratch.is_relative_to(scratch_root):
            raise ValueError("Invalid build scratch directory")
        for name, data in files.items():
            path = scratch / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        wrapper = scratch / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        if os.name != "nt":
            wrapper.chmod(wrapper.stat().st_mode | 0o100)
        with (output / "build.log").open("wb") as log:
            subprocess.run([str(wrapper), "-o", "-q", "-DskipTests", "package"], cwd=scratch, stdout=log, stderr=subprocess.STDOUT, check=True)
        jars = [path for path in (scratch / "target").glob("*.jar") if not path.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if len(jars) != 1:
            raise ValueError("Expected exactly one packaged application JAR; inspect build.log")
        with zipfile.ZipFile(jars[0]) as archive:
            names = archive.namelist()
            if (not any(name.startswith("BOOT-INF/classes/") for name in names)
                    or not any(name.startswith("BOOT-INF/lib/") for name in names)
                    or any(Path(name).name.startswith(("application-local.", ".env")) for name in names)):
                raise ValueError("Build must be a Spring Boot JAR without local profiles or environment files")
        artifact = jars[0].read_bytes()
        (output / "stocksage-backend.jar").write_bytes(artifact)
    manifest = {"schema": "fundamentals_evolution_build_v1", "gitSha": revision, "sourceState": "WORKTREE_SNAPSHOT",
                "sourceTreeSha256": json_hash({name: hashlib.sha256(data).hexdigest() for name, data in files.items()}),
                "artifactSha256": hashlib.sha256(artifact).hexdigest(), "artifactFormat": "SPRING_BOOT_JAR",
                "builtAt": datetime.now(timezone.utc).isoformat()}
    (output / "build.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = build(args.repository, args.output)
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(2, f"Evaluation build failed ({type(error).__name__}); inspect {args.output / 'build.log'}. No accepted build manifest was issued.\n")
    print(json.dumps({"status": "BUILT", "artifact_sha256": manifest["artifactSha256"], "output": str(args.output)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
