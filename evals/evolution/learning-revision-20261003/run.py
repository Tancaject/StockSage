"""Registered, development-only revision of two previously verified learning groups."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
OLD = HERE.parent / "batch-learning-validation-20261002"
sys.path.insert(0, str(REPO / "evals"))
from evolution_learning_input import compact_request
from evolution_candidates import build_experience
from evolution_batch import compose_diagnostic_bundle
from evolution_ai_review import DEFAULT_CONFIG
from run_evaluation_agent import invoke
from run_ragas_eval import resolve_api_key


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def prepare():
    old_plan = read(OLD / "plan.json")
    for path, expected in old_plan["frozenFiles"].items():
        assert digest(REPO / path) == expected, path
    frozen = dict(old_plan["frozenFiles"])
    groups = read(OLD / "groups.json")
    for i, group in enumerate(groups, 1):
        original = OLD / f"generated/group-{i}/request.json"
        for member in group["members"]:
            for name, hash_name in (("tracePath", "traceSha256"), ("feedbackPath", "feedbackSha256")):
                path = OLD / member[name]
                assert hashlib.sha256(path.read_text(encoding="utf-8").encode()).hexdigest() == member[hash_name]
                frozen[str(path.relative_to(REPO)).replace('\\', '/')] = digest(path)
        revision = None
        if i == 1:
            revision = {"previousExperience": read(OLD / "generated/group-1/experience.json"),
                        "observedSideEffect": "旧经验要求全表还原，超出原错误反馈与用户问题。保留缺失声明核查，去掉无关全表计算及主动展示要求。",
                        "feedbackOrigin": "ENGINEER_SOURCE_REVIEW_OF_DEVELOPMENT_ARTIFACTS"}
        request = compact_request(read(original), revision=revision)
        save(HERE / f"group-{i}/request.json", request)
        frozen[str(original.relative_to(REPO)).replace('\\', '/')] = digest(original)
    files = [HERE / "run.py", REPO / "evals/evolution_learning_input.py",
             REPO / "evals/diagnostics/model-root-cause-20261002/probe.py",
             OLD / "groups.json", OLD / "generated/group-1/experience.json",
             OLD / "generated/group-1/bundle.json", OLD / "review-policy.json"]
    files += list(HERE.glob("group-*/request.json"))
    frozen.update({str(p.relative_to(REPO)).replace('\\', '/'): digest(p) for p in files})
    save(HERE / "plan.json", {
        "schema": "fundamentals_learning_revision_diagnostic_v1",
        "registeredAt": datetime.now(timezone.utc).isoformat(), "frozenFiles": frozen,
        "authority": "USER_AUTHORIZED_AI_DIAGNOSTIC_ONLY", "releaseEligible": False,
        "scope": "Two parallel generator calls; group 1 old versus revised analysis method on DEV 05,09,06. No final synthesis, transfer or activation.",
        "maxGeneratorCalls": 2, "maxBusinessCalls": 6, "retries": 0,
        "cases": [5, 9, 6], "armOrder": [["old", "revised"], ["revised", "old"], ["old", "revised"]],
        "selection": "05 is source failure; 09 is observed expansion case; 06 is preselected non-applicable passing control. Not the full original screening set.",
        "settings": {"model": "qwen3.8-max", "temperature": 0.7, "max_tokens": 4096,
                     "thinking_budget": 32768, "enable_thinking": True, "stream": True,
                     "deadlineSeconds": 600, "productionDeadlineSeconds": 300},
        "quality": "Existing fundamentals analysis rubric plus frozen gold/policy; source-bound AI review of required facts/points and extra claims. Truncation or transport failure is NO_DATA, never PASS. Complete answers over 300s retain semantic review but fail production timeliness.",
        "decision": "No selection or transfer in this diagnostic. Report 3-case paired semantic and completeness counts, original-failure correction, unrelated calculations, latency and usage. Any regression prevents claiming improvement. Single samples do not establish stable benefit.",
        "limits": "Direct provider analysis replay, not Java/full-chain acceptance. Both arms use identical transport and fixed evidence. Generator input and instruction changes are a joint intervention, not isolated causal effects. New group 2 is generation-only, not behavior-validated."})


def verify():
    for path, expected in read(HERE / "plan.json")["frozenFiles"].items():
        assert digest(REPO / path) == expected, path


def generate(i):
    verify()
    folder = HERE / f"group-{i}"
    assert not (folder / "attempt.json").exists(), "No retries"
    save(folder / "attempt.json", {"startedAt": datetime.now(timezone.utc).isoformat()})
    response = invoke(DEFAULT_CONFIG, read(folder / "request.json"), resolve_api_key())
    save(folder / "response.json", response)
    result = {"status": "NOT_GENERATED", "releaseEligible": False, "usage": response.get("usage")}
    try:
        assert response.get("model") == DEFAULT_CONFIG["model"]
        assert len(response["choices"]) == 1 and response["choices"][0]["finish_reason"] == "stop"
        proposal = json.loads(response["choices"][0]["message"]["content"])
        group = read(OLD / "groups.json")[i - 1]
        sources, artifacts = [], {}
        for member in group["members"]:
            trace_text = (OLD / member["tracePath"]).read_text(encoding="utf-8")
            feedback_text = (OLD / member["feedbackPath"]).read_text(encoding="utf-8")
            artifacts.update({member["traceSha256"]: trace_text, member["feedbackSha256"]: feedback_text})
            issuer = member["caseId"].split('-')[0].upper()
            sources.append({"caseId": member["caseId"], "split": "DEVELOPMENT", "origin": "PUBLIC_AUTHORIZED",
                            "issuerId": issuer, "issuerName": issuer, "traceSha256": member["traceSha256"],
                            "externalFeedbackSha256": member["feedbackSha256"],
                            "feedbackKind": "VERIFIED_CORRECTION", "issueType": "METHOD_ERROR"})
        parents = [read(OLD / "generated/group-1/experience.json")] if i == 1 else []
        spec = {**proposal, "version": 2 if parents else 1, "role": "FUNDAMENTALS",
                "taskType": "ORDINARY_FUNDAMENTALS", "sourceRunIds": [m["runId"] for m in group["members"]],
                "sources": sources, "parentExperienceHashes": [p["recordSha256"] for p in parents]}
        experience = build_experience(spec, artifacts, parents=parents)
        bundle = compose_diagnostic_bundle("baseline-v1", (OLD / "baseline-method.txt").read_text(encoding="utf-8"),
                                           [experience], group["fixedContractSha256"])
        save(folder / "experience.json", experience)
        save(folder / "bundle.json", bundle)
        result["status"] = "PROPOSED"
    except (AssertionError, ValueError, KeyError, TypeError) as error:
        result["errorType"] = type(error).__name__
    save(folder / "result.json", result)
    print(json.dumps({"group": i, **result}), flush=True)


def business():
    verify()
    # Human-readable scope review is recorded before spending business calls.
    assert read(HERE / "scope-review.json")["group1ApprovedForDiagnostic"] is True
    old = read(OLD / "generated/group-1/bundle.json")["bundles"][0]["method"]
    revised = read(HERE / "group-1/bundle.json")["method"]
    registration = {"oldMethodSha256": hashlib.sha256(old.encode()).hexdigest(),
                    "newBundleSha256": digest(HERE / "group-1/bundle.json"), "inputs": {}}
    for n in read(HERE / "plan.json")["cases"]:
        path = OLD / f"raw-development-{n:02}/development-{n:02}-1-1.response.json"
        original = read(path)
        baseline = (OLD / "baseline-method.txt").read_text(encoding="utf-8")
        for arm, method in (("old", old), ("revised", revised)):
            messages = json.loads(json.dumps(original["analysis"]["messages"]))
            count = sum(m["text"].count(baseline) for m in messages)
            assert count == 1
            for m in messages:
                m["text"] = m["text"].replace(baseline, method)
            target = HERE / f"input-{n:02}-{arm}.json"
            save(target, {"schema": "DERIVED_DIAGNOSTIC_INPUT_NOT_JAVA_RESPONSE", "caseId": original["caseId"],
                          "sourceSha256": digest(path), "analysis": {"messages": messages}})
            registration["inputs"][target.name] = digest(target)
    save(HERE / "business-registration.json", registration)
    probe = REPO / "evals/diagnostics/model-root-cause-20261002/probe.py"
    def pair(item):
        n, order = item
        for arm in order:
            with (HERE / f"{n:02}-{arm}.log").open("x", encoding="utf-8") as log:
                subprocess.run([sys.executable, "-X", "utf8", str(probe), "--source", str(HERE / f"input-{n:02}-{arm}.json"),
                                "--stage", "analysis", "--output", str(HERE / f"{n:02}-{arm}"),
                                "--deadline-seconds", "600"], stdout=log, stderr=subprocess.STDOUT, check=True)
            print(json.dumps({"case": n, "arm": arm, **read(HERE / f"{n:02}-{arm}/result.json")}), flush=True)
    plan = read(HERE / "plan.json")
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(pair, zip(plan["cases"], plan["armOrder"])))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "generate", "business", "verify"])
    args = parser.parse_args()
    if args.command == "generate":
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(generate, [1, 2]))
    else:
        {"prepare": prepare, "business": business, "verify": verify}[args.command]()
