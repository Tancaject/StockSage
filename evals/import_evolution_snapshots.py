"""Import authorized UTF-8 source artifacts into frozen cases without accessing the network.

The input's provenance is an operator/reviewer attestation, not an authorization
grant. Real files and labels belong in the separately controlled evaluation store.
Ranges are Unicode code-point offsets into exact decoded source bytes, not tokens.
"""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

from evolution_dataset import CASE_FIELDS, REAL_EVIDENCE_FIELDS, PROVENANCE_FIELDS, REAL_ORIGIN, REAL_SCOPE, _unique_object, exact_fields, validate_cases
from eval_common import sha256


def import_snapshots(manifest_path: Path, source_root: Path) -> list[dict]:
    raw_manifest = manifest_path.read_bytes()
    manifest = json.loads(raw_manifest.decode("utf-8"), object_pairs_hook=_unique_object)
    exact_fields(manifest, {"schema", "cases"}, "source manifest")
    if manifest["schema"] != "fundamentals_source_snapshot_import_v1":
        raise ValueError("unsupported source import schema")
    root = source_root.resolve(strict=True)
    cases = copy.deepcopy(manifest["cases"])
    if not isinstance(cases, list) or not cases:
        raise ValueError("source manifest requires nonempty cases")
    for case in cases:
        exact_fields(case, CASE_FIELDS | {"provenance"}, "source case")
        if case["schemaVersion"] != 2 or case["origin"] != REAL_ORIGIN or case["executionScope"] != REAL_SCOPE:
            raise ValueError("source case must explicitly declare the authorized frozen component scope")
        exact_fields(case["provenance"], PROVENANCE_FIELDS - {"sourceManifestSha256"}, "source provenance")
        case["provenance"]["sourceManifestSha256"] = sha256(raw_manifest.decode("utf-8"))
        for row in case["evidence"]:
            exact_fields(row, (REAL_EVIDENCE_FIELDS - {"rawText", "visibleText", "rawSha256", "visibleSha256"})
                         | {"rawArtifact", "visibleRange"}, "source evidence")
            relative = Path(row.pop("rawArtifact"))
            source = (root / relative).resolve(strict=True)
            if relative.is_absolute() or not source.is_relative_to(root):
                raise ValueError("source artifact must resolve within the declared source root")
            excerpt = row.pop("visibleRange")
            text = source.read_bytes().decode("utf-8")
            if (not isinstance(excerpt, list) or len(excerpt) != 2
                    or any(type(value) is not int for value in excerpt)
                    or not 0 <= excerpt[0] < excerpt[1] <= len(text)):
                raise ValueError("visibleRange must be a nonempty in-bounds Unicode code-point range")
            visible = text[excerpt[0]:excerpt[1]]
            row.update(rawText=text, rawSha256=sha256(text), visibleText=visible, visibleSha256=sha256(visible))
    validate_cases(cases)
    return cases


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        cases = import_snapshots(args.manifest, args.source_root)
        with args.output.open("x", encoding="utf-8", newline="\n") as target:
            target.writelines(json.dumps(case, ensure_ascii=False) + "\n" for case in cases)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Snapshot import rejected: {error}\n")
    print(json.dumps({"status": "SNAPSHOTS_IMPORTED", "case_count": len(cases), "answer_quality": "NO_DATA"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
