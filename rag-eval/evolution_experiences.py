"""Versioned method-experience registry. Each edit produces a new signed JSON snapshot.

Records remain content-addressed PROPOSED objects; lifecycle events never rewrite
their method text. This is offline method governance, not company Research Memory.
"""
from __future__ import annotations

import argparse
import copy
import json
from datetime import datetime, timezone
from pathlib import Path

from evolution_acceptance import (require_gate, seal, verified, require_hash, read_key,
                                  evaluator_hash, validate_run_context)
from evolution_candidates import validate_record, experience_matches, compile_candidate, export_eval_bundle, save_immutable
from evolution_dataset import exact_fields, nonempty, timestamp
from ordinary_answer_quality import json_hash, sha256

TRANSITIONS = {"PROPOSED": {"VALIDATED", "REJECTED"}, "VALIDATED": {"EVALUATED", "REJECTED"},
               "EVALUATED": {"APPROVED", "REJECTED"}, "APPROVED": {"REVOKED"}, "REJECTED": set(), "REVOKED": set()}


def empty_registry(key: bytes) -> dict:
    return seal({"kind": "EXPERIENCE_REGISTRY", "schemaVersion": 1, "revision": 0,
                 "parentRegistrySha256": None, "entries": {}, "events": [], "conflicts": []}, key)


def load_registry(artifact: dict, key: bytes) -> dict:
    body = verified(artifact, key, "EXPERIENCE_REGISTRY")
    exact_fields(body, {"kind", "schemaVersion", "revision", "parentRegistrySha256", "entries", "events", "conflicts"}, "experience registry")
    if body["schemaVersion"] != 1 or type(body["revision"]) is not int or body["revision"] < 0:
        raise ValueError("Unsupported registry version")
    if not isinstance(body["entries"], dict) or any(not isinstance(body[name], list) for name in ("events", "conflicts")):
        raise ValueError("Registry entries must be an object and audit histories must be lists")
    if body["revision"] > 0:
        require_hash(body["parentRegistrySha256"], "parent registry")
    elif body["parentRegistrySha256"] is not None:
        raise ValueError("The initial registry cannot have a parent")
    states = {}
    for digest, entry in body["entries"].items():
        exact_fields(entry, {"record", "state", "gateSha256", "evaluatorSha256", "evaluatedCandidateSha256"}, "registry entry")
        validate_record(entry["record"])
        if entry["record"]["kind"] != "RESEARCH_EXPERIENCE" or entry["record"]["schemaVersion"] != 2 or entry["record"]["recordSha256"] != digest:
            raise ValueError("Approved registries require immutable v2 experience records")
        require_hash(entry["gateSha256"], "registration E07 gate")
        require_hash(entry["evaluatorSha256"], "registration evaluator")
        if entry["evaluatedCandidateSha256"] is not None:
            require_hash(entry["evaluatedCandidateSha256"], "evaluated candidate")
        if entry["state"] not in TRANSITIONS:
            raise ValueError("Unsupported experience lifecycle state")
    for number, event in enumerate(body["events"], 1):
        exact_fields(event, {"sequence", "experienceSha256", "from", "to", "actor", "at", "reason", "evidenceRefs"}, "experience event")
        for field in ("actor", "reason"):
            nonempty(event[field], "event." + field)
        timestamp(event["at"], "event.at")
        for digest in event["evidenceRefs"]:
            require_hash(digest, "event evidence")
        previous = states.get(event["experienceSha256"])
        if (event["sequence"] != number or event["experienceSha256"] not in body["entries"] or event["from"] != previous
                or (previous is None and event["to"] != "PROPOSED")
                or (previous is not None and event["to"] != previous and event["to"] not in TRANSITIONS[previous])):
            raise ValueError("Registry lifecycle history is not continuous")
        states[event["experienceSha256"]] = event["to"]
    if states != {digest: entry["state"] for digest, entry in body["entries"].items()}:
        raise ValueError("Registry state differs from its audit history")
    for conflict in body["conflicts"]:
        exact_fields(conflict, {"left", "right", "status", "actor", "at", "reason", "evidenceRefs"}, "experience conflict")
        if conflict["left"] == conflict["right"] or any(conflict[name] not in states for name in ("left", "right")) or conflict["status"] not in {"OPEN", "RESOLVED"}:
            raise ValueError("Invalid experience conflict")
    return copy.deepcopy(body)


def _event(body, digest, state, actor, reason, evidence):
    nonempty(actor, "lifecycle actor")
    nonempty(reason, "lifecycle reason")
    if not evidence:
        raise ValueError("Lifecycle changes require linked independent evidence")
    for reference in evidence:
        require_hash(reference, "evidence reference")
    entry = body["entries"][digest]
    body["events"].append({"sequence": len(body["events"]) + 1, "experienceSha256": digest,
                           "from": entry["state"], "to": state, "actor": actor, "at": datetime.now(timezone.utc).isoformat(),
                           "reason": reason, "evidenceRefs": list(evidence)})
    entry["state"] = state


def _next(body, parent, key):
    body["revision"] += 1
    body["parentRegistrySha256"] = parent["payloadSha256"]
    result = seal(body, key)
    load_registry(result, key)
    return result


def register(artifact: dict, record: dict, source_artifacts: dict[str, str], gate_artifact: dict,
             key: bytes, actor: str, reason: str) -> dict:
    body = load_registry(artifact, key)
    gate = require_gate(gate_artifact, key)
    validate_record(record)
    if record["kind"] != "RESEARCH_EXPERIENCE" or record["schemaVersion"] != 2:
        raise ValueError("Register a v2 experience with version, prohibited inferences and source run IDs")
    digest = record["recordSha256"]
    if digest in body["entries"]:
        return artifact
    observed = set()
    for source in record["sources"]:
        for field in ("traceSha256", "externalFeedbackSha256"):
            text = source_artifacts.get(source[field])
            if not isinstance(text, str) or sha256(text) != source[field]:
                raise ValueError("Experience source/independent-feedback artifact is absent or changed")
        trace = json.loads(source_artifacts[source["traceSha256"]])
        replay, definition = trace.get("replay") or {}, trace.get("case_definition") or {}
        if (replay.get("caseId") != source["caseId"] or definition.get("caseId") != source["caseId"]
                or replay.get("origin") != source["origin"] or definition.get("origin") != source["origin"]
                or (replay.get("runContext") or {}).get("runMode") not in {"BASELINE", "DEVELOPMENT"}):
            raise ValueError("Experience provenance must point to a development execution, never holdout/validation output")
        if source["origin"] == "PUBLIC_AUTHORIZED" and (source["caseId"] not in gate["policy"]["cohorts"]["DEVELOPMENT"]["caseIds"]
                or replay.get("caseSha256") != gate["caseHashes"].get(source["caseId"])
                or definition.get("caseSha256") != replay.get("caseSha256")):
            raise ValueError("Experience source is outside the accepted development cohort")
        if source["origin"] == "PUBLIC_AUTHORIZED":
            validate_run_context(trace, gate["experimentId"], replay["runContext"]["runMode"])
        from evolution_reflection import verify_feedback
        feedback = verify_feedback(source_artifacts[source['traceSha256']], source_artifacts[source['externalFeedbackSha256']], gate_artifact, key)
        if (feedback['issueType'] != 'METHOD_ERROR' or source['feedbackKind'] not in {'INDEPENDENT_REVIEW', 'VERIFIED_CORRECTION'}
                or any(source[name] != feedback[name] for name in ('caseId', 'issuerId', 'issuerName'))):
            raise ValueError('Experience source must match independently signed method-error feedback')
        observed.add(replay.get("runId"))
    if observed != set(record["sourceRunIds"]):
        raise ValueError("Experience sourceRunIds do not match the linked traces")
    parents = record["parentExperienceHashes"]
    if any(parent not in body["entries"] for parent in parents) or record["version"] != 1 + max(
            (body["entries"][parent]["record"]["version"] for parent in parents), default=0):
        raise ValueError("Parent experience versions must resolve in the registry")
    signature_fields = ("method", "triggerTags", "requiredEvidence", "requiredCapabilities", "applicabilityBoundary", "forbiddenInferences")
    duplicate = next((other for other, entry in body["entries"].items() if all(entry["record"][name] == record[name] for name in signature_fields)), None)
    body["entries"][digest] = {"record": copy.deepcopy(record), "state": None, "gateSha256": gate_artifact["payloadSha256"],
                              "evaluatorSha256": evaluator_hash(), "evaluatedCandidateSha256": None}
    _event(body, digest, "PROPOSED", actor, reason, [source["externalFeedbackSha256"] for source in record["sources"]])
    if duplicate:
        _event(body, digest, "REJECTED", actor, "Duplicate method and applicability; retain provenance only", [duplicate])
    return _next(body, artifact, key)


def transition(artifact: dict, digest: str, target: str, key: bytes, actor: str, reason: str,
               evidence: dict, *, candidate: dict | None = None) -> dict:
    body = load_registry(artifact, key)
    entry = body["entries"][digest]
    if target not in TRANSITIONS[entry["state"]]:
        raise ValueError("Illegal experience transition: " + entry["state"] + " -> " + target)
    if target == "VALIDATED":
        exact_fields(evidence, {"experienceSha256", "reviewer", "reviewedAt", "methodOnly", "sourceAuthorized", "injectionReview", "applicabilityReview"}, "method review")
        if (evidence["experienceSha256"] != digest or evidence["reviewer"] != actor
                or any(evidence[name] != "PASS" for name in ("methodOnly", "sourceAuthorized", "injectionReview", "applicabilityReview"))):
            raise ValueError("Independent static/source/applicability review has not passed")
        timestamp(evidence["reviewedAt"], "method review time")
        refs = [json_hash(evidence)]
    elif target in {"EVALUATED", "APPROVED"}:
        qualified = verified(evidence, key, "RELEASE_EVIDENCE")
        if (qualified.get("schema") != "fundamentals_evolution_release_evidence_v2"
                or qualified.get("evaluatorSha256") != entry["evaluatorSha256"]
                or qualified.get("acceptanceGateSha256") != entry["gateSha256"]):
            raise ValueError("Evaluation must use the registered E07 gate and evaluator")
        validate_record(candidate)
        if candidate["kind"] != "METHOD_CANDIDATE" or candidate["experienceRecordSha256"] != digest:
            raise ValueError("Evaluation belongs to another compiled experience")
        bundle = export_eval_bundle(candidate, qualified["fixedContractSha256"])["bundles"][0]
        if bundle["contentSha256"] != qualified["candidateBundleSha256"]:
            raise ValueError("Evaluation candidate bytes differ from the compiled experience")
        if target == "APPROVED" and (qualified["evaluationSplit"] != "HOLDOUT" or qualified["eligible"] is not True
                or qualified.get("authorization") != "PENDING_HUMAN_APPROVAL"
                or qualified["comparisonStatus"] != "IMPROVED" or entry["evaluatedCandidateSha256"] != candidate["recordSha256"]):
            raise ValueError("Approval needs accepted holdout evidence for the same evaluated candidate")
        if unavailable_reason(body, digest) is not None:
            raise ValueError("Conflicting or withdrawn experience lineage cannot be evaluated/approved")
        entry["evaluatedCandidateSha256"] = candidate["recordSha256"]
        refs = [evidence["payloadSha256"], candidate["recordSha256"]]
    else:
        nonempty(evidence.get("reason"), "rejection/revocation evidence")
        require_hash(evidence.get("artifactSha256"), "rejection/revocation artifact")
        refs = [evidence["artifactSha256"]]
    _event(body, digest, target, actor, reason, refs)
    return _next(body, artifact, key)


def conflict(artifact: dict, left: str, right: str, key: bytes, actor: str, reason: str, evidence_ref: str, *, resolve=False) -> dict:
    body = load_registry(artifact, key)
    if left == right or any(digest not in body["entries"] for digest in (left, right)):
        raise ValueError("Conflict must identify two registered experience versions")
    existing = [row for row in body["conflicts"] if {row["left"], row["right"]} == {left, right}]
    if resolve and (not existing or existing[-1]["status"] != "OPEN" or
                    all(body["entries"][digest]["state"] not in {"REJECTED", "REVOKED"} for digest in (left, right))):
        raise ValueError("Resolve by withdrawing/rejecting one incompatible version; refine methods in new versions")
    for digest in (left, right):
        _event(body, digest, body["entries"][digest]["state"], actor, reason, [evidence_ref])
    body["conflicts"].append({"left": left, "right": right, "status": "RESOLVED" if resolve else "OPEN",
                               "actor": actor, "at": datetime.now(timezone.utc).isoformat(), "reason": reason, "evidenceRefs": [evidence_ref]})
    return _next(body, artifact, key)


def unavailable_reason(body: dict, digest: str, visited=None) -> str | None:
    visited = set() if visited is None else visited
    if digest in visited:
        return "INVALID_PARENT_GRAPH"
    visited = visited | {digest}
    entry = body["entries"].get(digest)
    if entry is None or entry["state"] in {"REVOKED", "REJECTED"}:
        return "WITHDRAWN_OR_MISSING_LINEAGE"
    if entry["evaluatorSha256"] != evaluator_hash():
        return "EVALUATOR_CHANGED"
    conflicts = {}
    for row in body["conflicts"]:
        conflicts[frozenset((row["left"], row["right"]))] = row["status"]
    if any(digest in pair and state == "OPEN" for pair, state in conflicts.items()):
        return "UNRESOLVED_METHOD_CONFLICT"
    for parent in entry["record"]["parentExperienceHashes"]:
        reason = unavailable_reason(body, parent, visited)
        if reason:
            return reason
    return None


def select_approved(artifact: dict, key: bytes, task_tags: set[str], evidence_tags: set[str], capabilities: set[str]) -> dict:
    body = load_registry(artifact, key)
    matches = [entry["record"] for digest, entry in body["entries"].items() if entry["state"] == "APPROVED"
               and unavailable_reason(body, digest) is None and experience_matches(entry["record"], task_tags, evidence_tags, capabilities)]
    return {"selection": "EXPERIENCE" if len(matches) == 1 else "BASELINE",
            "reason": "ONE_APPROVED_MATCH" if len(matches) == 1 else "NO_APPROVED_MATCH" if not matches else "AMBIGUOUS_APPROVED_METHODS",
            "experience": copy.deepcopy(matches[0]) if len(matches) == 1 else None, "registrySha256": artifact["payloadSha256"]}


def load_approved(path: Path, key: bytes, task_tags: set[str], evidence_tags: set[str], capabilities: set[str]) -> dict:
    try:
        return select_approved(json.loads(path.read_text(encoding="utf-8")), key, task_tags, evidence_tags, capabilities)
    except (OSError, ValueError, KeyError, TypeError) as error:
        return {"selection": "BASELINE", "reason": "REGISTRY_LOAD_FAILED", "errorType": type(error).__name__, "experience": None, "registrySha256": None}


def compile_registered(artifact: dict, digest: str, key: bytes, parent_bundle: str, parent_method: str, proposal: dict,
                       task_tags: set[str], evidence_tags: set[str], capabilities: set[str], *, generation_mode='EXTERNAL_PROPOSAL') -> dict:
    body = load_registry(artifact, key)
    entry = body["entries"][digest]
    if (entry["state"] not in {"VALIDATED", "EVALUATED", "APPROVED"} or unavailable_reason(body, digest) is not None
            or not experience_matches(entry["record"], task_tags, evidence_tags, capabilities)):
        raise ValueError("Candidate cannot use an unvalidated, conflicting, withdrawn or inapplicable experience")
    return compile_candidate(parent_bundle, parent_method, proposal, entry["record"], generation_mode=generation_mode)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path)
    parser.add_argument("--operation", required=True, choices=["init", "register", "transition", "conflict", "resolve", "compile"])
    parser.add_argument("--input", type=Path, help="Operation-specific JSON request, including actor/reason and artifact paths")
    parser.add_argument("--output", required=True, type=Path, help="New immutable registry version, or candidate for compile")
    args = parser.parse_args()
    try:
        key = read_key()
        read = lambda path: json.loads(Path(path).read_text(encoding="utf-8"))
        if args.operation == "init":
            result = empty_registry(key)
        else:
            registry, request = read(args.registry), read(args.input)
            if args.operation == "compile":
                candidate = compile_registered(registry, request["experienceSha256"], key, request["parentBundleId"],
                                               Path(request["parentMethod"]).read_text(encoding="utf-8"), read(request["proposal"]),
                                               set(request["taskTags"]), set(request["evidenceTags"]), set(request["capabilities"]))
                save_immutable(args.output, candidate)
                print(json.dumps({"status": "PROPOSED", "candidateId": candidate["candidateId"],
                                  "recordSha256": candidate["recordSha256"], "registrySha256": registry["payloadSha256"]}))
                return 0
            if args.operation == "register":
                artifacts = {digest: Path(path).read_text(encoding="utf-8") for digest, path in request["sourceArtifacts"].items()}
                result = register(registry, read(request["record"]), artifacts, read(request["gate"]), key, request["actor"], request["reason"])
            elif args.operation == "transition":
                result = transition(registry, request["experienceSha256"], request["target"], key, request["actor"], request["reason"],
                                    read(request["evidence"]), candidate=read(request["candidate"]) if request.get("candidate") else None)
            else:
                result = conflict(registry, request["left"], request["right"], key, request["actor"], request["reason"],
                                  request["evidenceRef"], resolve=args.operation == "resolve")
        with args.output.open("x", encoding="utf-8") as target:
            target.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Experience registry operation rejected: {error}\n")
    print(json.dumps({"status": "REGISTRY_WRITTEN", "revision": result["payload"]["revision"], "registry_sha256": result["payloadSha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
