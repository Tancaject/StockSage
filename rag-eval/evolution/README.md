# Evolution datasets and offline evaluation

`synthetic-smoke/` contains 12 **SYNTHETIC** cases and separately stored, generated fact records. They exercise data plumbing and deterministic comparisons only. They are not real company research, independently reviewed labels, a protected holdout, or evidence of improved research quality. Research-set acceptance is tracked in [progress.md](../../progress.md).

The schema authority is [`../evolution_dataset.py`](../evolution_dataset.py). `cases.jsonl` contains exact case IDs, source snapshots, report groups, dates, and hashes; `gold.jsonl` binds each record to a case ID and canonical case hash. All four dimensions reuse `ordinary_answer_quality.DIMENSIONS` (`claim_support`, `numeric_period_correctness`, `counterevidence`, `unknowns`), remain `NO_DATA`, and have `humanReviewStatus: UNREVIEWED`.

The 12 cases cover normal values, zero, negative values, missing values, reporting periods, units, currencies, restatements, issuer mismatch, old evidence, document prompt injection, and an omitted raw-only appendix. Wrong-issuer and old evidence are intentionally accepted inputs: validating the dataset does not certify that such evidence supports the requested answer. The injection fixture tests transport of untrusted content; its existence does not demonstrate successful injection resistance.

`execution_manifest()` creates an allowlisted `{schemaVersion, cases, runs}` document. Version 2 additionally registers `context` with `experimentId`, `evaluatorVersion` and `runMode`; signed E07 and paired evaluation require this identity. Each execution case carries the original `query`, separate `resolvedQuery`, user/assistant-only `history`, ISO local-date `asOf`, source `context`, and hashes. Synthetic context is exactly the ordered concatenation of `[E#] ` plus each `visibleText`, separated by two newlines. Raw-only text and gold are excluded. Synthetic source metadata required during execution is present in visible text itself.

The explicit synthetic profile is `executionScope: SYNTHETIC_COMPONENT_CHAIN`, `ticker: issuerId`, empty `requestAttributes`, `timeSensitivity: HISTORICAL`, and `initialTaskOutcome: DEGRADED`. These are fixture inputs included in the case hash, not observed production outcomes or values derived from gold. `preAnalystContext` is a snapshot of the production evidence-prefix assembly, bound by its own hash; the Java loader rebuilds it with `ToolPrefetchService.ordinaryEvidenceContext` to detect drift. `citationIds` preserves evidence order. The smoke cases have empty history and no RAG or history-memory inputs; they do not reproduce all production inputs.

Each manifest export creates fresh UUID run IDs with explicit `caseId`, `bundleId: baseline-v1`, and `repeatId: 1`. The run plan does not change the stable case hash. The checked-in `execution.json` is one registration manifest; exporting another creates a distinct run plan. This is not a claim of durable exactly-once execution or experimental quality gain.

Raw/visible SHA-256 values hash the exact UTF-8 text. `case_hash()` hashes the complete case as compact JSON with sorted keys, `ensure_ascii=False`, and no final newline; Java may echo this hash without reserializing the case. `contextSha256` hashes the actual exported context. These hashes bind bytes and provenance, not truth or trusted origin.

`validate_cases()` rejects cross-split reuse of a leakage group, issuer/report-family pair, source reference, raw hash, or visible hash, and rejects duplicate case IDs. Paraphrases, overlapping excerpts with different hashes, and unregistered related reports still require explicit grouping and independent review; this validator does not perform semantic deduplication. Keep a report family, its restatements, and its query variants in the same group. All supplied fixtures are in `SMOKE` and must not be promoted into a research holdout.

`compare_fact_records()` compares separately supplied structured records with exact decimal values, preserving units, currencies, periods, and null-versus-zero. It does not parse free-form answers, convert currencies or units, derive financial formulas, or assign a semantic score. Matching records produce `MECHANISM_PASS` with `answer_quality: NO_DATA`; empty comparisons produce `NO_DATA`. Source facts from an irrelevant issuer or obsolete report do not become accepted answer claims merely by matching.

Run from `rag-eval` with Python (standard library only):

```powershell
python -m unittest test_evolution_dataset
python evolution_dataset.py evolution/synthetic-smoke/cases.jsonl --gold evolution/synthetic-smoke/gold.jsonl
python evolution_dataset.py evolution/synthetic-smoke/cases.jsonl --gold evolution/synthetic-smoke/gold.jsonl --export-execution execution.json
```

`--export-execution PATH` exports execution inputs after validation and requires a new path, preserving registered run plans. Gold and execution files are separated for code-path hygiene in this public synthetic fixture, not access-controlled storage for a real held-out evaluation.

## Authorized frozen source import

[`import_evolution_snapshots.py`](../import_evolution_snapshots.py) imports local UTF-8 source artifacts without network access. Its `fundamentals_source_snapshot_import_v1` manifest contains `cases`; the case/evidence field authority is [`evolution_dataset.py`](../evolution_dataset.py). For each evidence row, replace `rawText / visibleText / rawSha256 / visibleSha256` with a relative `rawArtifact` and `[start, end]` `visibleRange` in Unicode code points. Paths must resolve inside `--source-root`. The importer computes all text hashes and `provenance.sourceManifestSha256` from the exact input files.

Authorized cases use schema version 2, `origin: PUBLIC_AUTHORIZED`, and `executionScope: FROZEN_COMPONENT_CHAIN`. Provenance records authorization reference, operator/reviewer identities, capture/review times and the research instant. These are attestations for independent review, not permissions granted by the importer. Source metadata retains business period, source version, units/currency, publication, availability and retrieval times; unknown period/unit/currency values remain null. Availability cannot exceed the frozen research instant. Private material needs separate authorization and de-identification before import.

The exported model context includes each source's sorted metadata JSON followed by its visible text. Java reconstructs and checks this exact context plus the production analyst prefix before model execution. Raw-only text, authorization/reviewer provenance and gold are not model messages. This exercises the frozen two-stage component chain; it does not reproduce live retrieval, RAG or user memory.

```powershell
python import_evolution_snapshots.py --manifest source-manifest.json --source-root authorized-sources --output source-cases.jsonl
python evolution_dataset.py source-cases.jsonl --gold independent-gold.jsonl --export-execution execution.json
```

Source gold version 2 adds independent reviewer/time, `numericPolicy`, `derivations`, `requiredPoints` and `expectedResponse` (`ANSWER / REFUSE / CLARIFY`). `validate_real_expectations()` is the schema authority. Numeric policy freezes decimal-place rounding (`HALF_EVEN`) and absolute tolerance in the declared units. Derived outputs require two labeled source inputs and a fixed operation (`SUM / DIFFERENCE / RATIO / PERCENT_CHANGE / PERCENT_OF`); no expression evaluation, implicit currency conversion or division by zero is allowed. This validates labeled arithmetic, not whether a person labeled the source correctly. Gold remains in the independent evaluator's store, including for the final holdout.

## Source-bound answer review

For authorized runs, both review export and application require the unchanged source cases and independently reviewed gold:

```powershell
python evolution_quality.py --input baseline-results.json --source-cases source-cases.jsonl --gold independent-gold.jsonl --review-template baseline-review.json
python evolution_quality.py --input baseline-results.json --source-cases source-cases.jsonl --gold independent-gold.jsonl --reviews baseline-review.json --output baseline-reviewed.json
```

The evaluator re-exports each source case and requires exact equality with the recorded execution input. Each stage review binds the original answer/prompt/evidence and gold hash. Reviewers retain the four semantic dimensions and additionally attest to authorization, tenant isolation, security identity, time, budget and output-contract gates. These human gates require independent runtime/access evidence; a source hash alone cannot satisfy them.

The independent reviewer extracts numerical claims with exact answer/evidence excerpts and a support judgment, marks extraction completeness, rates every required task point with an answer excerpt, and classifies answer/refusal/clarification. The evaluator checks visible excerpt binding, citation ownership, units/currency/periods, null-versus-zero, tolerance and required-fact coverage against the frozen labels. It reports separate numerical, citation-support and task-coverage counts; a zero denominator means no applicable evidence, not 100%. Claim extraction and semantic support remain human judgments: the evaluator does not pretend to parse arbitrary prose or certify reviewer independence from a name string. Evidence text is never executed or treated as evaluator instructions.

Missing reviews/labels remain `NO_DATA`; observed errors yield `FAIL`. Fully bound real reviews can yield `PASS` for those outputs, which is not proof of improvement or permission to publish. Synthetic reviews always retain `answer_quality: NO_DATA`, even with passing mechanism checks. The fictional fixture in backend test resources tests the authorized-source wire format only.

## Two-stage Java replay

The Java service runs `FundamentalsAgent`, the shared production draft assembly, `ChatPromptAssembler`, and `OrdinaryAnswerReplayService`. It does not repeat retrieval or write conversations/reports/memory. Run it in a dedicated evaluation instance: the Spring profile alone is not an operating-system network or storage sandbox.

Register these startup properties using the existing application setup:

- `spring.profiles.active=evolution-eval` (include other required environment profiles separately).
- `stocksage.evolution.eval.case-file`: the exported execution JSON, with fresh registered run IDs.
- Optional `stocksage.evolution.eval.bundle-file`: an experimental bundle export. Parents must precede children. This allowlist is separate from the serving registry.
- `stocksage.evolution.eval.build-manifest`: the matching `build.json` from the isolated build below. E07 requires an attested packaged runtime; omission permits mechanism checks only.
- Existing admin-token configuration and model configuration. Do not store credentials in manifests.

The actual prompt limit and model timeout use the existing `stocksage.chat.prompt.max-text-chars` and `stocksage.agent.prefetch.timeout-seconds` properties. Result observations describe configured request options, response model identity when supplied, usage, exact prompts and visible evidence. Missing provider data stays unknown. `comparisonIdentity.modelConfigurationJson` preserves Java's exact JSON bytes for hashing; reserializing floating-point options in another language is not equivalent.

From `rag-eval`, after the dedicated service and its admin token are configured:

```powershell
python run_evolution_replay.py --input execution.json --output baseline-results.json --bundle-id baseline-v1 --max-runs 12
python evolution_quality.py --input baseline-results.json --review-template baseline-review.json
python evolution_quality.py --input baseline-results.json --reviews baseline-review.json --output baseline-reviewed.json
```

The HTTP request has exactly `caseId`, `bundleId`, and `runId`. Unknown combinations, arbitrary prompts, paths and model options are rejected. The same registered run cannot be resubmitted in one Java process, including after timeout/failure. A lost HTTP response may have incurred usage; the runner stops and never retries automatically. Its execution `PASS` is not a semantic pass.

The evaluation profile supplies a dedicated `evolutionReplayExecutor` for analyst calls: one worker, no waiting queue and no caller-thread fallback. Concurrent excess work returns `REPLAY_CAPACITY_EXHAUSTED`; it cannot occupy `agentTaskExecutor` slots. This isolates local analyst execution capacity, not provider-wide quotas, HTTP threads or operating-system resources. Use the dedicated evaluation instance required above and retain the independent resource/isolation checks.

### Isolated build and registered experiment context

From the repository root, build from a frozen copy of Git-visible backend inputs:

```powershell
python rag-eval/evolution_build.py --output stocksage-backend/target/evolution-build
```

The output directory must be new. The builder packages offline using the existing Maven cache and the cached wrapper after verifying its versioned checksum. It excludes local application profiles and environment files even if they were added to Git. It emits `stocksage-backend.jar`, `build.json` and `build.log`; compilation failure emits no accepted manifest. The source state is explicitly `WORKTREE_SNAPSHOT`: `gitSha` identifies the base commit, while `sourceTreeSha256` binds the copied inputs, including uncommitted changes. The JAR hash covers packaged classes, resources and dependency bytes. This does not certify the compiler, machine or human review.

Export the baseline execution file from `rag-eval`:

```powershell
python evolution_dataset.py source-cases.jsonl --gold independent-gold.jsonl --export-execution baseline-execution.json --experiment-id <frozen-experiment-id> --run-mode BASELINE
```

Use `DEVELOPMENT`, `VALIDATION`, `HOLDOUT` or `SHADOW` for the corresponding predeclared run. The exporter records the evaluator source fingerprint automatically. Launch the packaged JAR with the registered case file and matching `stocksage.evolution.eval.build-manifest`; a class-directory launch cannot satisfy this build contract. Java checks the actual startup artifact hash against the manifest before replay. Results bind the registered experiment/evaluator/mode, Git/build identity, run/repeat IDs, method bundle, model configuration, evidence, history, memory and fixed final prompt. Python E07/comparison rejects changed or missing bindings. Runtime provenance is a prerequisite for acceptance, not evidence of answer quality or environmental isolation.

For a predeclared paired experiment, both bundles' runs share one execution file. The legacy `--bundle-id` mode selects one arm; the v2 `--paired` mode below alternates arms. Supply `--comparison-manifest comparison.json` to validate and bind the comparison plan **before** calls. The comparison schema is owned by [`evolution_compare.validate_manifest`](../evolution_compare.py). Preserve the original plan; generating it after viewing outcomes is not preregistration.

## E07 frozen acceptance and evaluator authority

[`evolution_acceptance.py`](../evolution_acceptance.py) owns the acceptance policy, baseline gate, resource audit and candidate-selection contracts. The policy freezes cohort membership, repetitions, key groups, primary rubric dimension, minimum meaningful improvement, non-regression margins, statistical procedure, alternating execution/no automatic retries, candidate/round/call/token limits and monetary/latency limits. Thresholds need a rationale from the actual baseline; the unit-test thresholds are fictional. Missing prices remain null and prevent a monetary release gate from passing.

E07 requires complete, independently reviewed development/validation baseline runs with provider token usage and consistent actual model identities. It binds the source dataset and separate gold, baseline output, policy, evaluator source fingerprint, and independent review of negative controls, isolation, calibration and the original sampling plan. The evidence index maps each declared SHA-256 to an original local proof file; missing/changed proof bytes are rejected. Baseline quality failures can be recorded as optimization targets; missing labels or hard-gate evidence cannot authorize search.

```powershell
python evolution_acceptance.py --baseline baseline-reviewed.json --source-cases source-cases.jsonl --gold independent-gold.jsonl --policy frozen-policy.json --review e07-review.json --evidence-index evidence-index.json --output e07-gate.json
```

Only the independent evaluator environment receives `STOCKSAGE_EVOLUTION_EVALUATOR_KEY` (at least 32 secret bytes). The stdlib HMAC signature authenticates the evaluator artifact and its purpose; it does not establish that a human attestation is true or implement OS process/credential separation. Keep that key, original proofs, labels and holdout files out of the optimizer environment and Git. A `UNIT_TEST` gate stays `MECHANISM_READY` and `require_gate()` rejects it for real optimization. Changing evaluator code invalidates previously accepted gates. The trusted orchestration callback must call `require_gate()`; a callback that does nothing is not approval.

The v2 comparison manifest references `acceptanceGateSha256`, an `evaluationSplit` (`DEVELOPMENT / VALIDATION / HOLDOUT`) and a `selectionSha256` (null before holdout). Its statistics, case hashes, cohorts, repetitions, baseline and execution identities must match the E07 gate. Development comparison can filter candidates but cannot select a final candidate or grant release eligibility. Execute both arms in the independent evaluation environment:

```powershell
python run_evolution_replay.py --paired --input execution.json --comparison-manifest validation-plan.json --acceptance-gate e07-gate.json --experiment-ledger protected-eval/experiment.sqlite --resource-reservations resource-reservations.json --max-runs 60 --output validation-runs
```

`--max-runs` bounds both arms together in paired mode. The output directory must be new. It contains baseline/candidate reports and a flushed `invocations.jsonl` recording submissions, returned responses and restored records. The first arm alternates per pair. Returned execution failures remain in the planned sample count; an ambiguous transport response stops further calls without retry. The shared SQLite resource ledger provides admission and resume; the journal is an evidence export rather than a second authority for spend.

## Resource audit and v2 decisions

The independent resource audit lists every analyst, final-answer, generator and Judge invocation, including failed and unknown attempts and earlier candidates. Entries bind original invocation proofs and a complete ledger snapshot; actual tokens/costs remain nullable. `seal_resource_audit()` checks the original proof artifacts. The evaluator separately attests ledger completeness, candidate/round counts and method-versus-infrastructure failure attribution. Replay-stage entries are rechecked against actual observed usage, result hashes and the execution order. The signature and file hashes do not replace provider billing reconciliation or discovery of otherwise unobserved SDK retries.

```powershell
python evolution_acceptance.py --resource-audit resource-review.json --evidence-index resource-proof-index.json --output resource-audit.json
python evolution_compare.py --manifest validation-plan.json --baseline baseline-reviewed.json --candidate candidate-reviewed.json --source-cases source-cases.jsonl --gold independent-gold.jsonl --acceptance-gate e07-gate.json --resource-audit resource-audit.json --output validation-comparison.json
```

V2 checks both stages and each preregistered key group. It reports separate numeric, citation-support, task-coverage, appropriate-refusal, appropriate-clarification and answerable-case response metrics. No applicable cases is N/A, never a perfect score. Known candidate execution failures score failed/unreviewable stages as zero rather than disappearing from the denominator; infrastructure failures remain explicitly inconclusive and all costs remain counted. Hard violations, increased critical numerical errors or breached limits yield `NO_IMPROVEMENT`. Missing evidence, model drift, insufficient groups or uncertain non-regression yield `INCONCLUSIVE`. `IMPROVED` requires the final-answer primary delta to reach the frozen effect size with an uncertainty lower bound above zero; analysis-only improvement is reported separately.

## Select one candidate and consume the holdout once

Validation improvement permits selection, not release. The independent evaluator freezes one candidate from signed validation evidence:

```powershell
python evolution_acceptance.py --select-result validation-comparison.json --reviewer <independent-reviewer-id> --reason <selection-reason> --output selected-candidate.json
python run_evolution_replay.py --paired --input holdout-execution.json --comparison-manifest holdout-plan.json --acceptance-gate e07-gate.json --selection selected-candidate.json --holdout-ledger protected-eval/holdout.sqlite --experiment-ledger protected-eval/experiment.sqlite --resource-reservations resource-reservations.json --max-runs 60 --output holdout-runs
```

The HOLDOUT manifest binds `selectionSha256`. Before calls, the protected SQLite ledger reserves source/report-family/leakage-group identities for that exact candidate and manifest. A changed experiment ID, renamed case or partially overlapping cohort cannot reset consumed source ownership. Both arms may use the same reservation; it is not a new independent holdout in another iteration. Preserve this evaluator-owned ledger across runs.

For final comparison, pass `--selection selected-candidate.json --holdout-ledger protected-eval/holdout.sqlite` alongside the E07 gate, holdout resource audit, signed `--review-audit` and independently reviewed holdout reports. Only accepted holdout evidence is signed with `eligible: true / PENDING_HUMAN_APPROVAL`. That permits E13 publication preparation; it neither approves a deployment nor changes the serving method. The optimizer must not receive detailed holdout failures.

## Candidate records and offline orchestration

[`evolution_candidates.py`](../evolution_candidates.py) owns the experience/candidate schema. `build_experience()` verifies the supplied development trace and external-feedback text hashes. `attribute_failure()` separates method failures from missing data, unsupported capabilities, provider failures, executor errors and evaluator failures. `experience_matches()` is an offline prerequisite check; it does not authorize runtime injection.

`compile_candidate()` freezes one complete `fundamentals.method` replacement. `generate_candidate()` calls an injected generator once, only after the trusted caller's E07 gate succeeds. Every artifact remains `PROPOSED / UNREVIEWED / NOT_RUN`; regex checks are explicit tripwires, not proof of natural-language safety. There is no provider credential, holdout reader or publishing client in this module.

Import an externally produced proposal and optionally export its exact method to the Java evaluation registry:

```powershell
python evolution_candidates.py --proposal proposal.json --experience experience.json --parent-bundle baseline-v1 --parent-method baseline-method.txt --output candidate.json --eval-bundle-output eval-bundles.json --fixed-contract-sha256 <current-baseline-fixed-contract-sha256>
```

The contract hash comes from the current trusted build/baseline identity. `recordSha256` binds the audit record; Java `contentSha256` separately binds the method bundle. Neither hash authorizes production activation. The serving registry accepts only its application-owned approved artifacts.

### Shared experiment resource ledger

[`evolution_experiment.ExperimentLedger`](../evolution_experiment.py) verifies the signed E07 gate directly and owns one experiment's frozen resource contract. Paired replay requires `--experiment-ledger` and `--resource-reservations`; keep the same protected SQLite file across candidates, rounds and evaluation phases. Changing the output directory does not reset spend. A database from a different ledger contract is rejected rather than silently treated as empty. Ledger code and replay admission code are included in the evaluator fingerprint, so changes require renewed E07 acceptance.

The reservation file has exactly `schema: fundamentals_resource_reservations_v1`, `experimentId` and `roles`. `roles` has all four keys `ANALYST`, `FINAL_ANSWER`, `GENERATOR` and `JUDGE`. Each role declares:

| Field | Meaning |
|---|---|
| `maxTokens` | Positive conservative reservation for input plus output tokens per invocation. |
| `maxCostUsd` | Nonnegative decimal string, or null if no cost ceiling is frozen. |
| `inputUsdPerMillion` / `outputUsdPerMillion` | Frozen nonnegative decimal-string rates, or null if unknown. |

An experiment with a monetary ceiling requires prices and cost reservations for every role. A reservation cannot be lower than the maximum token charge under its rates. All roles share the accepted E07 role-call, total-token and experiment-cost limits. The ledger commits reservations before calling the supplied adapter. Generator and Judge adapters must use this same ledger; recording analyst/final calls alone is not a complete search audit. Full search orchestration status is maintained in [progress.md](../../progress.md).

Costs are explicitly `FROZEN_RATE_CARD_ESTIMATE`, not reconciled provider invoices. Provider token observations determine recorded usage; absent tokens/prices remain null and block new submissions. An observed reservation overrun is retained and stops later calls; local reservation checks cannot undo a provider's incurred charge. A pending or lost-response submission remains potentially billed and is never retried automatically. A completed response can be restored to rebuild the same report, preserving original run IDs and plan start time; it is not a new independent repeat. `--stop-file PATH` prevents the next new submission while the file exists, while already recorded responses remain readable.

The ledger's `events` records each model action's `SUBMITTED → COMPLETED` or `SUBMITTED → UNKNOWN` transition in the same SQLite transaction as its state change. Events retain sequence, action ID, previous/new state, the local process account (`actor`), UTC time, reason and hashes of exported original proofs. This actor identifies the executing account, not an independent reviewer or a grant of authority. Submission evidence is committed before the remote call; abrupt process termination can leave a last `SUBMITTED` event, which must not be relabelled as a confirmed remote failure. `COMPLETED` means the response was durably recorded, not that the model answer passed quality checks; missing or invalid usage still blocks further calls. Restoring a recorded response does not append a new execution or transition. Older ledgers are not given invented historical events.

Paired output includes `resource-review/ledger.json`, original invocation proofs, `evidence-index.json` and `audit-template.json`. The template defaults to `complete: false` and leaves reviewer, counts and failure attribution unapproved. An independent reviewer must check completeness, fill the actual candidate/round counts and classify all replay outcomes before signing. The signer rejects omitted or changed entries relative to this ledger, including generator/Judge calls and unknown submissions. For this ledger format it also checks continuous action history, agreement with each current action state, and every referenced original event proof; a historical ledger without those events cannot acquire them by assertion. Files outside the ledger and unobserved transport retries still require independent audit; file hashes alone cannot discover them.

```powershell
python evolution_acceptance.py --resource-audit validation-runs/resource-review/audit-template.json --evidence-index validation-runs/resource-review/evidence-index.json --output resource-audit.json
```

This command signs the reviewed resource artifact; incomplete or unknown data cannot satisfy comparison gates. Replay CLI exit `0` means all declared replay responses completed with usable resource accounting, `3` means blocked/incomplete execution, and `4` means the next submission would exceed a frozen budget. None is an answer-quality improvement decision.

## Bounded search controller

[`run_evolution_experiment.py`](../run_evolution_experiment.py) implements the `validate / search / compare` command modes using the existing component replay and independent comparison paths:

```powershell
python run_evolution_experiment.py --manifest experiments/iteration-001/manifest.json --mode validate
python run_evolution_experiment.py --manifest experiments/iteration-001/manifest.json --mode search
python run_evolution_experiment.py --manifest experiments/iteration-001/manifest.json --mode compare
```

The manifest schema is `fundamentals_evolution_search_v1`; `validate_plan()` is its field authority. Required identities are `experimentId`, `acceptanceGateSha256`, `registrySha256`, `sourceCasesSha256` (canonical JSON hash of the source list) and `baselineMethodSha256` (exact UTF-8 text). `experienceHashes` is the fixed ordered list of independently validated experience records. `taskTags`, `evidenceTags` and `capabilities` declare their prerequisite match. The input cases must contain exactly the E07 development and validation cohorts; the controller rejects holdout sources. The baseline text must reproduce the accepted Java bundle hash.

`roundLimit`, `noImprovementLimit` and `validationLimit` are positive frozen bounds within the E07 candidate/round limits. Each round proposes one method from the next experience in cyclic order, retaining the same baseline parent. The controller promotes a method only after an independent development `IMPROVED` result, sends at most `validationLimit` candidates to validation, and nominates the first independent validation improvement. Failed, inconclusive, invalid or duplicate proposals remain in the history and cost ledger. The configured consecutive unsuccessful-candidate limit or round limit ends the search without relaxing criteria. The frozen development cohort is the search batch; selecting that cohort is part of E07 preparation, not a post-result resampling step.

The remaining manifest fields specify local paths and the fixed generator:

| Field | Contract |
|---|---|
| `inputs` | Exactly `gate`, `registry`, `sources`, `baselineMethod`, `reservations`, each a path relative to the manifest directory. Sources use JSONL; baseline method uses UTF-8 text; the others use JSON. |
| `workspace` | Relative output directory for immutable search contract, rounds and content-addressed reports. |
| `ledger` | The shared experiment SQLite path used by generation **and** paired replay. |
| `generator` | Exactly `endpoint`, `model`, `temperature`, `maxOutputTokens`, `timeoutSeconds`. The endpoint is an OpenAI-compatible chat-completions URL using HTTPS or loopback HTTP, without embedded credentials. |

All paths must remain inside the manifest directory. `validate` verifies and freezes the contract without model calls. `search` can submit a new generator call; `compare` consumes already recorded generator responses and independently signed comparison results without issuing generator calls. The shared ledger also binds one search manifest, so choosing a new output directory cannot restart round/candidate limits within that experiment. Changing frozen inputs requires another reviewed experiment rather than rewriting the existing contract. Serialization uses atomic creation; interrupted or concurrent writers cannot publish partial or different candidate inputs under the same path.

The fixed HTTP adapter is [`evolution_generator.py`](../evolution_generator.py). Set `STOCKSAGE_EVOLUTION_GENERATOR_KEY` only in the protected controller environment, separate from the evaluator key. The remote optimizer receives a fixed system contract, the accepted baseline, one validated procedural experience and previous **development decisions**. It does not receive validation/holdout answers, gold, evaluator credentials, publisher credentials or tools. Endpoint, model, temperature, output cap and timeout cannot be supplied by a generated proposal. Redirects, tool calls, truncated/multiple proposals, changed observed models and invalid method edits are rejected; known failed proposals retain their observed usage. The serialized request size plus output cap must fit the frozen generator reservation before submission; this is a conservative byte-based admission check, while provider token observations remain authoritative.

Generation is reserved in the shared ledger before the HTTP call. Missing credentials stop before submission. A lost response blocks the experiment without automatic retry; a stored response is restored without another billable call. Budget/stop handling uses the same ledger as paired replay. The adapter does not install a model SDK or add retry behavior.

For each valid candidate, the controller emits `round-NNN/candidate.json`, `bundles.json`, and `development/execution.json` plus `comparison.json`. It returns `PENDING_DEVELOPMENT`. Register those exact files in the isolated Java instance, run the paired replay CLI with the same ledger/reservations, collect independent reviews and resource approval, and run the existing comparison command. Save its complete signed result as `development/comparison-result.json`. Resume `compare` or `search`: an accepted development result emits the full validation plan and returns `PENDING_VALIDATION`; save that independent result as `validation/comparison-result.json`. These pauses preserve the startup-only Java allowlist and independent review boundary; the search command does not restart services or manufacture labels.

The controller checks the complete comparison report's signature-bound hash, decision purpose, candidate, cohort, evaluator and original manifest before progressing. Changing metrics, exclusions or other report content invalidates that comparison even when its signed conclusion is left unchanged. Validation details do not return to the generator. Duplicate method content cannot become another candidate replay or an extra independent sample. Reports retain candidate/source identities, parent bundle, generation invocation, rejection reasons, comparison-evidence references and the cumulative resource snapshot; full per-case/group reports remain in their authoritative comparison artifacts.

Run one search controller at a time per experiment ledger. `resources.searchEvents` preserves search and candidate transitions in that protected SQLite file. Each event records its search-manifest hash, entity, prior/new state, executing local account, UTC time, reason and original proof hashes. Search states include creation/validation, controller start or resume, waits for generation/review, blocking, cancellation, budget exhaustion and completion. A candidate records proposal, static validation or rejection, development/validation review and rejection or `NOMINATED`; nomination does not mean selected for holdout, approved or active. Each candidate milestone is immutable: resuming it adds no duplicate event, and replacing an already-consumed comparison with another signed decision is rejected. Both rejected and nominated rounds retain `outcome.json` (static rejection uses `rejected.json`). A completed search restores its recorded conclusion without rerunning generation or reinterpreting later edits to comparison files; a revised experiment requires independent acceptance and a new ledger. Resource-review export includes these proofs, and signing verifies continuous history and their hashes. The events attribute execution to the local account; independent reviewer/publisher authority remains in the separate signed records.

Exit codes: `0` means validation or the bounded search completed, `2` means a contract/storage error, `3` means waiting for input/environment or blocked execution, and `4` means budget exhaustion. An ending search can report `NO_IMPROVEMENT` or `INCONCLUSIVE`; `VALIDATION_IMPROVED` merely nominates a candidate with `releaseEligible: false`. Final independent selection, one-use holdout, publication approval and activation remain separate operations. This workflow does not claim that automated rewriting beats a separately frozen human checklist.

## Versioned method-experience lifecycle

[`evolution_experiences.py`](../evolution_experiences.py) owns the offline registry, lifecycle audit, deterministic matching and governed candidate compilation. Immutable v2 `RESEARCH_EXPERIENCE` records add `version`, `sourceRunIds` and `forbiddenInferences` to the method/source/applicability contract in [`evolution_candidates.py`](../evolution_candidates.py). Candidate and trace artifacts remain in experimental storage. The registry is versioned JSON; lifecycle changes create a new signed snapshot and preserve original method bytes and history. It does not write company Research Memory or attach methods to live requests.

Run the registry commands only in the trusted evaluator environment with its evaluator key. The optimizer must not receive this key. Actor/reviewer strings are audit attestations; filesystem and credential separation still require deployment controls.

```powershell
python evolution_experiences.py --operation init --output registry-000.json
python evolution_experiences.py --operation register --registry registry-000.json --input registration.json --output registry-001.json
python evolution_experiences.py --operation transition --registry registry-001.json --input static-review-request.json --output registry-002.json
python evolution_experiences.py --operation compile --registry registry-002.json --input compile-request.json --output candidate.json
```

All outputs require new paths. Request file fields are:

| Operation | Request fields |
|---|---|
| `register` | `record`, `gate` (JSON paths), `sourceArtifacts` (SHA-256 to UTF-8 artifact path), `actor`, `reason` |
| `transition` | `experienceSha256`, `target`, `evidence` (JSON path), optional `candidate` (JSON path), `actor`, `reason` |
| `conflict` / `resolve` | `left`, `right` (experience hashes), `evidenceRef`, `actor`, `reason` |
| `compile` | `experienceSha256`, `parentBundleId`, `parentMethod` (text path), `proposal` (JSON path), `taskTags`, `evidenceTags`, `capabilities` (lists) |

Registration verifies original trace/feedback bytes, independently signed `DEVELOPMENT_FEEDBACK`, matching issuer identity, source run IDs and parent versions. Both independent reviews and verified corrections require that signed method-error feedback; a matching file hash alone is insufficient. Authorized sources must belong to the accepted E07 development cohort and retain matching experiment/runtime provenance; validation and holdout results cannot become reflection input. Identical methods and applicability are recorded as rejected duplicates with provenance retained. Semantic contradiction detection remains an independent review responsibility; reviewers register incompatible pairs explicitly.

The lifecycle is `PROPOSED → VALIDATED → EVALUATED → APPROVED`, with rejection before approval and revocation after approval. Static validation records an independent reviewer/time and passing method-only, source-authorization, injection and applicability reviews. Evaluation binds signed v2 comparison evidence to the exact compiled candidate, original E07 gate and evaluator. Approval additionally requires eligible holdout improvement and the same evaluated candidate. Experience approval does not grant production activation.

`compile_registered()` accepts only validated, applicable methods without open conflicts or withdrawn ancestry. `select_approved()` / `load_approved()` are read-only offline selection helpers: only one approved match can be selected; missing conditions, ambiguity, revoked ancestry, changed evaluators or invalid/unreadable registries return an explicit baseline decision. Conflict resolution requires rejection/revocation of an incompatible version; refinements use new records. Online use remains through an independently approved, fully evaluated method bundle, never dynamic append of a newly selected experience. Publishing and activation acceptance are tracked in [progress.md](../../progress.md).

## Development reflection and defect routing

[`evolution_reflection.py`](../evolution_reflection.py) consumes one development execution and independently reviewed findings, including failed executions or rejected answers. `--trace` is one complete case object from the replay report's `cases` array, preserved as UTF-8 JSON; `--source` is its authorized source-case object. Only cases in the accepted E07 development cohort, with matching execution inputs and `BASELINE` or `DEVELOPMENT` run context, qualify. Validation and holdout traces are rejected.

The evaluator first prepares and signs feedback in its protected environment:

```powershell
python evolution_reflection.py --mode review-template --trace trace.json --source source.json --gate gate.json --output feedback-review.json
python evolution_reflection.py --mode sign-feedback --trace trace.json --source source.json --gate gate.json --review feedback-review.json --output feedback-signed.json
```

Before signing, the independent reviewer fills `issuerName`, `reviewer`, `reviewedAt`, `issueType` and `findings`. Each finding has exactly `location`, `excerpt` and `reason`. `location` is a JSON pointer to a scalar under `/replay/` or `/case_definition/`, such as `/replay/analysis/answer` or `/replay/errorCode`; its excerpt must actually appear there. Signing binds the exact trace bytes, frozen source hash, issuer ID, case/run and E07 experiment. Reviewer identity and attribution are attestations that still require independent human judgment and access controls; signatures do not prove their truth.

| Independent attribution | Repair target | Output queue |
|---|---|---|
| `METHOD_ERROR` | `fundamentals.method` | `METHOD_CANDIDATE` |
| `MISSING_DATA` | `source_data` | `DATA_QUEUE` |
| `UNSUPPORTED_CAPABILITY` | `capability` | `DEFECT_QUEUE` |
| `EXECUTOR_ERROR` | `executor` | `DEFECT_QUEUE` |
| `PROVIDER_FAILURE` | `provider` | `PROVIDER_QUEUE` |
| `EVALUATION_FAILURE` | `evaluator` | `EVALUATION_QUEUE` |

Non-method findings create an `OPEN` attribution record without a model call or method candidate. These are local queue records for the responsible workflow, not automatically sent tickets. For method findings, run the fixed generator through the same experiment resource ledger:

```powershell
python evolution_reflection.py --mode reflect --trace trace.json --feedback feedback-signed.json --gate gate.json --registry registry-000.json --generator-config generator.json --ledger experiment.sqlite --resource-reservations resources.json --output reflection-001
```

The generator configuration uses the [search controller's fixed fields](#bounded-search-controller). `STOCKSAGE_EVOLUTION_GENERATOR_KEY` stays in the trusted controller environment. Generator requests receive the signed feedback's payload, located observations, development query and parent experiences; the evaluator key, signature and gold are excluded. Calls use the shared `GENERATOR` reservation and retain provider responses and usage. A completed identical request can be restored without a provider key or new call. Unknown submission outcomes remain charged/reserved and cannot be retried. `--stop-file` stops new submissions.

An externally prepared structured proposal can instead be supplied with `--proposal proposal.json`, omitting generator configuration and ledger arguments. This imports an existing proposal and makes no model call; any prior external model usage must still appear in the experiment's independent resource audit. The proposal has exactly `issueType`, `repairTarget`, `method`, `triggerTags`, `requiredEvidence`, `requiredCapabilities`, `applicabilityBoundary`, `counterexamples` and `forbiddenInferences`; the independent attribution cannot be changed by the proposal. Optional repeated `--parent-experience HASH` values must resolve to available registry records.

Every invocation requires a new output directory. `attribution.json` retains the failure locations, repair target, source/feedback hashes and decision. Illegal, malformed or company-specific proposals produce `REJECTED` with a reason and no experience; model costs remain in the ledger. Valid methods additionally produce immutable `experience.json` and `registration-request.json`. They remain `PROPOSED / UNREVIEWED / NOT_RUN`, with `SINGLE_DEVELOPMENT_CASE / UNVERIFIED` generalization evidence. Register them through the [experience lifecycle](#versioned-method-experience-lifecycle) for duplicate/conflict handling and independent injection/applicability review before governed compilation. Neither reflection nor registration modifies company Research Memory, published methods or serving requests.

Exit codes: `0` means a template, signed feedback, proposal or defect record was written; `2` means invalid input/storage/transport failure; `3` means missing prerequisites or an ambiguous/blocked resource state; `4` means reservation exhaustion; `5` means a recorded rejected proposal. None means quality improvement or publication approval. Preserve the ledger and output artifacts after errors; do not retry ambiguous model submissions.

## Independent comparison

```powershell
python evolution_compare.py --manifest comparison.json --baseline baseline-results.json --candidate candidate-results.json --source-cases source-cases.jsonl --gold independent-gold.jsonl --blind-dir blind-review
python evolution_review.py --mode template --manifest comparison.json --baseline baseline-results.json --candidate candidate-results.json --mapping blind-review/mapping-private.json --reviews review-one.json review-two.json --source-cases source-cases.jsonl --gold independent-gold.jsonl --output adjudication-input
python evolution_review.py --mode finalize --manifest comparison.json --baseline baseline-results.json --candidate candidate-results.json --mapping blind-review/mapping-private.json --reviews review-one.json review-two.json --source-cases source-cases.jsonl --gold independent-gold.jsonl --adjudication adjudication-input/adjudication.json --output adjudicated
python evolution_compare.py --manifest comparison.json --baseline adjudicated/baseline-reviewed.json --candidate adjudicated/candidate-reviewed.json --source-cases source-cases.jsonl --gold independent-gold.jsonl --acceptance-gate gate.json --resource-audit resource-audit.json --review-audit adjudicated/review-audit.json --output comparison-result.json
```

Give each independent reviewer a copy of `blinded.json`; retain `mapping-private.json` separately. A/B order is randomized per trial, without version names in the review fields. The packet preserves original answers, evidence, execution status, errors and gold. Unexecuted stages remain explicit review items. Reviewers fill the existing dimension/claim fields plus each trial's `preference` (`A`, `B`, `TIE` or `UNREVIEWABLE`) and nonempty `reason`. Every packet must cover all trials and name one distinct reviewer with review timestamps; incomplete dimensions require `UNREVIEWABLE`. At least one independent review is required; supply every collected review to the same adjudication operation. Local hashes cannot discover reviews withheld from the evaluator or prove that different identity strings belong to independent people.

[`evolution_review.py`](../evolution_review.py) keeps all original opinions, including ties and losing arms. Different preferences, dimension statuses or structured claim judgments create a `DISPUTED` trial; narrative dimension reasons alone do not. The template freezes packet/material/mapping/manifest hashes and votes. For each dispute, a distinct adjudicator fills `resolution.reviewer`, `reviewedAt`, `reason` and `selectedPacketSha256`, selecting one retained review for that trial. Revised scoring requires a new full review packet and regenerated adjudication input; the original packets are never overwritten. Agreed trials need no adjudication. Preferences are audit judgments, not substitutes for the frozen numeric quality gates.

Finalize uses `STOCKSAGE_EVOLUTION_EVALUATOR_KEY` in the protected evaluator environment. Unresolved disputes return exit `3` with `PENDING_ADJUDICATION`, preserve the opinions and emit no approved review audit. Completed adjudication returns exit `0` with the two rebound reports and signed `review-audit.json`; malformed or changed inputs return `2`. Every output directory must be new. The signed audit binds the exact reviewed reports, comparison manifest, evaluator fingerprint, original packets and adjudication, with separate trial/dispute/tie/unreviewable counts. Retain the private mapping and original reports alongside these protected artifacts.

V2 comparisons require this audit in addition to E07 and resource evidence. Missing audit or unreviewable trials prevents an `IMPROVED` decision; changed signatures, reports, manifests or evaluator code are rejected. Release evidence includes `reviewAuditSha256`. The evaluator still checks actual model/provider/budget/evidence identities, revalidates claims, averages repeats within cases and bootstraps issuer/report-family groups. Repeated model calls are not new independent companies. No hard gate, failure denominator or source binding is waived by an adjudicator's preference.

For synthetic mechanism checks, omit source/gold inputs; V1 remains `INCONCLUSIVE / NOT_AUTHORIZED`. Existing single-packet `--blind-reviews --mapping` applies ratings but cannot satisfy V2 acceptance without the signed audit. These files are local artifacts, not a deployed access-control service. Keep holdout packets, private mappings, gold, original disagreements and adjudication details in the independent evaluator store; they must not be sent to this iteration's generator. Evaluator changes require renewed E07 acceptance.

## Publisher approval and startup validation packages

[`evolution_release.py`](../evolution_release.py) prepares one immutable release package from an approved experience and its exact compiled candidate. Its input JSON has exactly `candidate`, `registry`, `gate`, `releaseEvidence` and `buildIdentity`, each a JSON path relative to that input file. `releaseEvidence` is the signed V2 holdout artifact. `buildIdentity` is the complete `comparisonIdentity.runtimeBuild` object from the accepted replay, not a newly invented build manifest. Preparation verifies E07, current evaluator, eligible holdout improvement, source/gold/contract identity, review/resource/selection references, current approved experience state and exact bundle content. A missing, conflicting or revoked experience is rejected. The initial rollback target must be the accepted baseline.

```powershell
python evolution_release.py --mode prepare --input publisher-input.json --output release-prepared
python evolution_release.py --mode approve --input publisher-input.json --approval publisher-review.json --approval-evidence publisher-evidence-index.json --output release-approved
```

Preparation writes `package.json` and an unapproved `approval-template.json`. A publisher reviews the exact package, fills `approver`, `approvedAt`, `reason`, and six checks: `scope`, `negativeCases`, `cost`, `privacy`, `dependencies`, `rollback`. Each check requires `status: PASS` and `evidenceSha256`; the evidence-index JSON maps each digest to a file path relative to the index. Approval verifies those original proof bytes and recomputes the package from its inputs, so changing candidate, registry, build or evaluation evidence invalidates the earlier approval. Review statements remain human attestations; the signer cannot independently establish their substantive truth.

Run approval only in the protected publisher/build environment. It requires `STOCKSAGE_EVOLUTION_EVALUATOR_KEY` to authenticate incoming evaluation evidence and a **different** `STOCKSAGE_EVOLUTION_PUBLISHER_KEY` of at least 32 UTF-8 bytes to authorize publication. The optimizer must have neither credential. Approval outputs `approved-artifact.json` and a separate `publisher-approval.json`; prior output directories cannot be overwritten. The status is `APPROVED_PENDING_VALIDATION`, never active. Copy the unchanged accepted application JAR and these immutable artifacts through the protected deployment process; rebuilding the application changes its hash and requires evaluation of that build.

The publisher envelope is `fundamentals_publisher_artifact_v1`. It carries `payloadBase64`, `payloadSha256` and an HMAC-SHA256 `signature` over the exact decoded UTF-8 payload bytes. Base64 avoids Python/Java JSON reserialization differences. The approved payload binds the complete method package and separate approval hash. The deployment must pin the envelope's `payloadSha256` independently of the candidate file; a file's self-reported hash cannot authorize loading.

Backend startup accepts these protected deployment settings:

| Setting | Contract |
|---|---|
| `stocksage.evolution.fundamentals.approved-artifact` | Optional local path to the publisher-signed artifact. Empty loads no staged package. |
| `stocksage.evolution.fundamentals.approved-artifact-sha256` | Required deployment-pinned `payloadSha256` when an artifact is configured. |
| `STOCKSAGE_EVOLUTION_PUBLISHER_KEY` | Trusted runtime verification credential; never supplied by requests or exposed in traces. |
| `stocksage.evolution.fundamentals.revoked-bundle-ids` | Comma-separated operator-maintained denylist, frozen at startup. |
| `stocksage.evolution.control-directory` | Operator-owned directory read at shadow admission and between model calls; required to execute SHADOW runs. Missing/unreadable storage stops shadow execution. See [rollback controls](#rollback-controls-and-recorded-drill). |
| `stocksage.evolution.fundamentals.activation-artifact` | Optional separately signed `METHOD_ACTIVATION` record. Loading it does not change `active()` or `require()`; the scoped selector requires server-derived request facts. |
| `stocksage.evolution.fundamentals.activation-artifact-sha256` | Independently pinned payload hash for the activation record. A method package's pin cannot substitute for it. |

[`ApprovedMethodArtifact`](../../stocksage-backend/src/main/java/com/stocksage/evolution/ApprovedMethodArtifact.java) verifies signature, deployment pin, schema, method/contract hash, scope, supported prerequisites, baseline rollback identity, revocation and the actual packaged runtime identity. A class-directory launch or changed application/runtime identity cannot load the package. Configured invalid artifacts fail startup with recovery instructions; restore the approved deployment inputs or remove the staged artifact configuration to start with baseline. Keep the publisher credential and deployment settings outside optimizer-writeable storage. Local implementation checks do not establish actual host permission separation.

`approvedForValidation(id)` exposes the staged package for isolated validation. `active()` and `require(id)` stay restricted to baseline; setting the default serving bundle ID to a staged candidate fails. Scoped selection requires the separate authorization described below. Model, final-prompt and memory identities are retained for validation, not treated as already verified by merely loading a package. Publishing does not execute models, restart services, grant tenant access or satisfy shadow/rollback/activation gates. Withdrawal uses the operator controls below and retains the deployment denylist, immutable artifacts and audit history across restart. Ordinary request integration and acceptance are tracked in [progress.md](../../progress.md).

### Approved shadow replay boundary

An execution manifest with `context.runMode: SHADOW` must register baseline plus exactly one candidate in the evaluation bundle file. The candidate must equal the startup-loaded publisher-approved package, including its body and content hash; its approved runtime-build identity must match the actual replay build. Only `PUBLIC_AUTHORIZED` frozen source cases are accepted. Arbitrary experimental or synthetic bundles cannot be relabeled as approved shadow runs. The operator still registers exact case/bundle/run combinations and keeps the source snapshots in restricted evaluation storage.

Shadow results include `comparisonIdentity.shadow` with the publisher artifact, package and approval hashes, `outputScope: ISOLATED_REPLAY_ONLY` and `conditionsMatch`. The service compares the actual model configuration, fixed final prompt, memory and runtime-build hashes with the approved conditions. A completed model execution with changed conditions becomes `FAILED / SHADOW_APPROVED_CONDITIONS_CHANGED`; its answers and observed identities remain in the isolated result for independent review. This does not activate a package or grant quality/isolation acceptance.

### Rollback controls and recorded drill

Ordinary prefetch pins the method object before submitting the analyst; invocation and Trace attribution use that same object, including failures. Shadow replay likewise keeps its registered immutable bundle for both model stages. A normal withdrawal never substitutes baseline into an in-flight candidate run.

Provision the control directory outside optimizer-writeable storage. The backend only reads its immediate filenames; the operator creates empty marker files. Marker contents are not instructions or configuration. The following controls have separate scopes:

| Control | Effect |
|---|---|
| Generator CLI `--stop-file <controls>/STOP_GENERATION` | Stops new generation submissions through the existing search/ledger admission path. It does not stop shadow or ordinary requests. |
| `<controls>/STOP_SHADOW` | Backend refuses new SHADOW runs of either arm with `SHADOW_STOPPED_BY_OPERATOR`, including direct API submissions. Also pass this same file as the shadow runner's `--stop-file` to stop before budget reservation. Other evaluation modes and ordinary serving are unaffected. |
| `<controls>/REVOKE.<bundleId>` | Backend refuses new SHADOW runs of that staged candidate with `SHADOW_BUNDLE_WITHDRAWN`; the registered baseline arm can still execute. Revocation is latched in the current process. Retain the marker and add the bundle to the startup denylist before redeployment; removing a marker does not clear the running process's latch. |
| `<controls>/CANCEL_SHADOW` | Refuses new SHADOW runs and stops in-flight shadow work at the boundary after the analyst and before final-answer submission, with `SHADOW_CANCELLED_BY_OPERATOR`. It does not promise to interrupt a provider call already executing remotely. |
| `<controls>/PROHIBIT_ACTIVATION` | Blocks new drill/serving authorization signatures and makes the scoped selector choose baseline with `ACTIVATION_PROHIBITED`. Does not stop shadow validation. |
| `<controls>/STABLE_ONLY` | Blocks new authorization signatures and latches the staged package's withdrawal when the scoped selector next checks it. New selections use baseline with `BUNDLE_WITHDRAWN`; already pinned invocations keep their original method. Retain this marker across process restarts. |

Missing or unreadable control storage produces `SHADOW_CONTROL_UNAVAILABLE`; no new model call is submitted at the checked boundary. Each refusal retains the registered run ID, selected bundle and failure reason. A refused run is consumed, so removing a stop marker does not retry that run. Use the original ledger and a newly registered plan after operator review; never reset budget or history. Partial responses preserve already observed usage; absent provider usage remains unknown in the shared accounting, not an assumed zero.

For the deployment drill, retain the original signed artifacts, version list, execution manifests and raw responses. Record the candidate run, the marker creation time and fault evidence, the in-flight run's original bundle, the refused next candidate run, and a newly registered baseline run after withdrawal. Inspect the baseline run's actual prompt/bundle hashes and both model invocations to rule out reuse of a candidate answer. The isolated replay has no answer cache. Keep control files and audit artifacts when recovering the service. An unavailable/corrupt loader is recovered by restoring the accepted application/artifact inputs or starting baseline without a staged package, not by editing historical reports or deleting tables.

These controls do not grant activation authority. E15 acceptance also requires an approved preproduction drill of the serving stable switch and activation-denial control before E16. Save real fault-injection and recovery run IDs for all E15 faults (corrupt package, hash mismatch, experience withdrawal, model drift, evaluator failure and unavailable loader); local fixtures cannot certify that acceptance. Current implementation and pending acceptance are tracked in [progress.md](../../progress.md).

The [ordinary execution drill](../../stocksage-backend/src/test/java/com/stocksage/conversation/OrdinaryEvolutionDrillTest.java) runs four new requests through actual `ChatService`, prefetch, analyst, final-answer client, prompt assembler and Trace serialization. Its model, routing and persistence boundaries use local test doubles. It withdraws during the second analyst call, checks that call's pinned candidate, then verifies two fresh baseline analyst/final calls. Each run's raw Trace, response events, identity and withdrawal time are saved under a new `target/evolution-drills/ordinary-*` directory; the producer prints that exact path.

From `stocksage-backend`, run:

```powershell
$evaluatorHash = python -c "import sys; sys.path.insert(0, '../rag-eval'); from evolution_acceptance import evaluator_hash; print(evaluator_hash())"
.\mvnw.cmd -q '-Dtest=OrdinaryEvolutionDrillTest' "-Devolution.evaluator-sha256=$evaluatorHash" test
python ..\rag-eval\check_evolution_drill_exports.py --input <printed-directory>/offline-drill.json --output <printed-directory>/verification.json
```

The export checker uses the same `verify_drill_runs` implementation as signed rollback acceptance. It verifies original Trace hashes, actual analyst method/prompt identities, intact final prompt/evidence capture, four distinct run IDs, fixed evidence and the withdrawal timeline. Its output is only `OFFLINE_ORDINARY_DRILL_VERIFICATION / VERIFIED_ORDINARY_RUN_CHAIN`, explicitly `realAcceptance: false`. It never manufactures fault proofs, signs acceptance or authorizes serving. Startup loader/signature checks remain separate; production `accept-drill` still requires the original approved artifacts, independent reviewer and every fault/control proof. Changed evaluator/package bytes require regenerating the producer batch rather than editing exported traces.

### Drill authorization, rollback acceptance and scoped authorization

[`evolution_deployment.py`](../evolution_deployment.py) signs three distinct records. The publisher's `METHOD_ACTIVATION / DRILL` authorizes only a reviewed preproduction exercise; the backend rejects it without the `evolution-drill` profile. Independent `ROLLBACK_ACCEPTANCE` is evaluator-signed and binds original Trace exports, the fault/control proofs and the original drill authorization. A new publisher-signed `METHOD_ACTIVATION / SERVING` additionally requires that acceptance. These commands only create immutable records; they neither deploy a server nor change its active method.

Use `--input` for a JSON object of artifact paths relative to that file:

| Mode | Input keys |
|---|---|
| `authorize-drill` | `approvedArtifact`, `shadowAcceptance` |
| `accept-drill` | `approvedArtifact`, `drillAuthorization` |
| `authorize-serving` | `approvedArtifact`, `shadowAcceptance`, `drillAuthorization`, `rollbackAcceptance` |

```powershell
python evolution_deployment.py --mode authorize-drill --input drill-input.json --review drill-review.json --evidence-index proofs.json --control-directory operator-controls --output drill-authorized
python evolution_deployment.py --mode accept-drill --input rollback-input.json --review rollback-review.json --evidence-index proofs.json --output rollback-accepted
python evolution_deployment.py --mode authorize-serving --input serving-input.json --review serving-review.json --evidence-index proofs.json --control-directory operator-controls --output serving-authorized
```

Keep `STOCKSAGE_EVOLUTION_EVALUATOR_KEY` and `STOCKSAGE_EVOLUTION_PUBLISHER_KEY` distinct and protected. `proofs.json` maps each SHA-256 to the original file path relative to that index. The output directory must be new. Authorization writes `activation-authorization.json`; acceptance writes `rollback-acceptance.json`. Authorization checks require readable operator control storage and reject `PROHIBIT_ACTIVATION`, `STABLE_ONLY` or `REVOKE.<bundleId>`. They never remove a marker or reset an experiment ledger.

An authorization review contains `approvedArtifactSha256`, `shadowAcceptanceSha256`, a new `activationId`, `approver`, timezone-aware `approvedAt`/`expiresAt`, `reason`, explicit `internalAccountIds` and `checks`. Serving also requires `rollbackAcceptanceSha256`. Account IDs are exact identities (up to 32 characters), not wildcard patterns. The drill accounts must be within the independently reviewed shadow scope and its expiry within the raw-data retention boundary. Serving accounts must be within the accepted drill and shadow scope. Expansion requires new reviewed records; changing the allowlist of an existing file invalidates its signature and deployment pin.

Each check is exactly `{"status":"PASS","evidenceSha256":"<original-proof-sha256>"}`. Drill authorization requires `internalAccounts`, `preproductionIsolation`, `runtimeConditions`, `applicability`, `retention`. Serving authorization requires `internalAccounts`, `runtimeConditions`, `applicability`, `monitoring`, `retention`. These are attestations backed by original proof bytes; successful parsing or signing does not establish their operational truth.

A rollback review contains `drillAuthorizationSha256`, a `reviewer` distinct from the drill approver, `reviewedAt`, `withdrawnAt`, `runs` and `checks`. Its required checks are the `ROLLBACK_CHECKS` constant in [the verifier](../evolution_deployment.py): all six specified fault injections, four independent switches, in-flight version stability, no candidate-cache reuse and preserved history. The four `runs` keys are `candidate-before`, `candidate-inflight`, `candidate-denied`, `baseline-after`. Each records `traceSha256`, `runId` and `caseSha256`. The latter is the canonical JSON hash of `{"query": <Trace userQuery>, "evidenceSha256": <answer-context evidence hash>}`.

The verifier reads actual `/api/trace/{traceId}` exports, accepting `steps` as the API's JSON string or a decoded array. It requires completed ordinary fundamentals analysis and a new final-answer invocation in each run. The `ordinary-evidence` step must carry its selected `methodBundle`, `analystCompletedAt`, and `methodSelection` with `authorizationSha256`, `mode`, `pinnedAt`, `caseSha256`, `comparisonIdentity`, and `reason`. Its `analystInvocation` must bind the actual system/user prompt hashes and executed method. The final-answer capture must pass the shared text-prompt/source-evidence completeness and hash checks in `ordinary_answer_quality.context_errors`. Both candidate runs must retain the approved candidate; post-withdrawal runs must report baseline with `BUNDLE_WITHDRAWN`. The in-flight run must straddle the withdrawal instant; before/after runs must occur on their respective sides. All four exports must bind the same fixed question/evidence and distinct run IDs within the authorized accounts and time window. Missing trace fields cannot be replaced with a human PASS assertion.

The backend [activation loader](../../stocksage-backend/src/main/java/com/stocksage/evolution/MethodActivation.java) authenticates the publisher envelope, exact purpose/schema, separate deployment pin, approved package/approval hashes, applicability scope, frozen comparison identities, account list and prior-acceptance references. The scoped registry selector accepts no candidate ID and returns an immutable selected bundle plus the actual reason and pin time. Unauthorized accounts, expired authorization, missing evidence/capabilities or unverified request conditions select baseline. A changed memory snapshot selects baseline only for that request; changed model/fixed-prompt/build conditions latch withdrawal. Loading a record alone never changes the baseline-only `active()`/`require()` APIs. See [current integration and acceptance status](../../progress.md) before attempting a deployment drill.

`ChatService` constructs the ordinary request identity from the authenticated account, actual selected final-answer options, fixed prompt and a single frozen profile/research-memory/RAG snapshot reused by final assembly. `FundamentalsRuntimeIdentity` shares canonical configuration/hash construction with replay; the approved loader verifies the build hash against its actual build identity. Selection is limited to ordinary text FUNDAMENTALS with the STANDARD tier and matching verified conditions. Image, alternate-model and prepared-context-only requests retain baseline. No request DTO accepts a candidate ID, applicability tags or comparison hashes.

`OrdinaryEvidence` supplies task tags `fundamentals` and the requested report period, plus `period-comparison` for a multi-report request with qualified dated values. It supplies `financial-evidence` only for available, usable, included, unabridged financial-report evidence without a qualification gap; the typed SEC, baostock and HK report contracts additionally supply `dated-values` and `structured-financials`. Source-provided tag strings confer no authority. Packages requiring other tags remain outside scope. The analyst's no-tool capabilities are `evidence-reading`, `period-comparison`, `unit-comparison` and `arithmetic`. This does not change evidence budgets or provider qualification rules.

Ordinary Trace records the selected method and reason before execution, the completed invocation's actual prompt hashes, `analystCompletedAt`, `analystDurationMs`, and `analystUsage`. Duration includes the local scheduling/analysis interval; provider usage is distinct from SDK-normalized or missing metadata. A timeout or rejected submission never fabricates zero usage. Normal withdrawal leaves the in-flight immutable method unchanged; subsequent selections read the operator controls.

### Serving observation and operator withdrawal

[`evolution_observation.py`](../evolution_observation.py) observes a fixed FUNDAMENTALS batch exported by `run_ordinary_live_eval.py`. Supply the original `ordinary_execution_eval_v1` result, publisher-approved package and matching signed SERVING authorization. Answer-only replay reports are not serving observations. Include failures and cancellations; an incomplete export retains the declared missing count. Coverage describes this exported batch, never all production traffic. Duplicate Trace IDs cannot inflate sample counts.

Quality review reuses the existing ordinary-answer rubric and actual answer/prompt/evidence bindings. A separate operational review binds the entire export plus each case/run/Trace/answer hash, named reviewer and timestamp. Its `appropriateRefusal` and existing `HARD_GATES` checks each contain `status` (`PASS`, `FAIL`, `NOT_APPLICABLE`, `NO_DATA`) and a rationale for any assessed status. `errorCategory` is `NONE`, `METHOD`, `DATA`, `CAPABILITY`, `EXECUTOR`, `EVALUATOR` or `UNKNOWN`. Keep reviews and raw exports in restricted storage; human assertions still require actual inspection of source and operational evidence.

```powershell
python ordinary_answer_quality.py --input serving-execution.json --review-template serving-quality-reviews.json
python evolution_observation.py --report serving-execution.json --approved-artifact approved-artifact.json --activation serving-authorized/activation-authorization.json --review-template --output serving-operational-reviews.json
# Complete the two reviews against the unchanged original export before this command.
python evolution_observation.py --report serving-execution.json --approved-artifact approved-artifact.json --activation serving-authorized/activation-authorization.json --quality-reviews serving-quality-reviews.json --reviews serving-operational-reviews.json --withdraw-on-failure --control-directory operator-controls --output serving-observation.json
```

The observation contains per-bundle counts/distribution, selection reasons, quality/refusal outcomes, error categories, error/cancellation/unsuccessful rates, whole-request and analyst P95 latency, and separate analyst/final-answer usage. P95 uses the existing linear interpolation helper and includes observed unsuccessful-run durations, with missing-duration counts. Usage takes the last final-answer invocation snapshot, never sums streaming cumulative snapshots. Only nonnegative integer provider tokens contribute to observed subtotals. Missing/partial metadata has explicit coverage and null totals; no provider invoice or whole-agent cost is inferred. Metadata output omits questions, answers and account IDs; source/review hashes identify the restricted evidence.

Candidate account/authorization/runtime/version violations, assessed quality/refusal failures or failed hard gates produce `STOP_EXPANSION_AND_WITHDRAW` (exit `3`). Unknown or incomplete execution/review evidence yields `HOLD_SCOPE_UNCERTAIN` (exit `4`); otherwise the conclusion is `MAINTAIN_REVIEWED_SCOPE` (exit `0`). None authorizes expansion. Invalid inputs exit `2`; a review template exits `0`. Embedded old `quality_review` labels are not trusted: pass the original quality-review input for binding checks.

With the explicit `--withdraw-on-failure` option, a detected failure writes `PROHIBIT_ACTIVATION` and `REVOKE.<candidateBundleId>` to the existing operator control directory before saving the observation. These are the same controls consumed by the backend selector; already pinned calls retain their method. Without the option the command only reports the required action. Repeated withdrawal retains markers and history. Missing/unwritable control storage is an error requiring operator intervention; never infer successful withdrawal from an unwritten marker or automatically clear markers after a later passing batch. The operator invokes this batch workflow; it is not a continuously running monitor. Scope expansion requires a separately reviewed publication/authorization chain.

`operatorMarkers` retains the list of control names. The accompanying `operatorAction` records the local executing account, UTC start/completion times, each marker's prior/new presence, failure reason and original execution/activation/review hashes. A non-withdrawing assessment has no action (`null`). Repeating the command records already-present markers without attributing their original creation to the new action. `WITHDRAWAL_MARKERS_WRITTEN` proves only the local write; `runtimeWithdrawal: UNVERIFIED` requires subsequent backend Trace evidence. The local account is an audit attribution, not publisher authorization. Retain the output in protected operator storage. If writing controls or saving the output fails, preserve any existing markers and record the partial operation in the independent operational evidence; the command does not clear them or claim a completed runtime rollback. Manual control-file edits and deployment changes also require operator identity, time, prior/new state, reason and original proof in that operational evidence.

### Bounded shadow batch and independent acceptance

[`evolution_shadow.py`](../evolution_shadow.py) prepares a fixed batch using exactly the accepted E07 **validation** source cases and repetition counts, with new run IDs and `runMode: SHADOW`. It reuses that authorized cohort to check the approved package under deployment conditions; it does not reuse holdout, generate a new candidate, or claim new independent samples. Pass only the validation cases to preparation. The comparison retains the existing numerical, grouped non-regression, hard-gate, cost and latency limits. Serving continues to use baseline on the separate normal instance.

```powershell
python evolution_shadow.py --mode prepare --gate gate.json --source-cases validation-sources.jsonl --approved-artifact approved-artifact.json --output restricted-shadow/prepared
python evolution_shadow.py --mode authorize --gate gate.json --source-cases validation-sources.jsonl --approved-artifact approved-artifact.json --plan restricted-shadow/prepared/shadow-plan.json --review shadow-authorization-review.json --evidence-index shadow-authorization-proofs.json --output restricted-shadow/authorized
```

The preparation output includes `authorization-review.json`. A publisher fills `approver`, `approvedAt`, explicit `internalAccountIds`, the exact dedicated evaluation `endpoint`, an absolute `restrictedStore`, and `retentionUntil` (timezone-aware timestamps). The five authorization checks are `accountScope`, `isolatedInstance`, `modelOnlyEgress`, `restrictedStorage`, and `baselineDelivery`; each requires `status: PASS` and an `evidenceSha256` resolving through the proof-index file. Account ownership, host permissions, actual network restrictions and baseline delivery require independently reviewed operational evidence; strings in this record do not establish them automatically.

Authorization binds the exact plan, approved package, endpoint and execution-file bytes. It exports `comparison.json`, `execution.json`, `eval-bundles.json` and a publisher-signed `shadow-authorization.json`. Register the unchanged execution/bundle files on the dedicated Java instance with the E13 approved artifact and matching build manifest. Shadow input preparation and authorization require distinct evaluator and publisher credentials in the trusted controller environment; the remote optimizer receives none of these credentials or records.

```powershell
python run_evolution_replay.py --paired --input restricted-shadow/authorized/execution.json --comparison-manifest restricted-shadow/authorized/comparison.json --acceptance-gate gate.json --approved-artifact approved-artifact.json --shadow-authorization restricted-shadow/authorized/shadow-authorization.json --experiment-ledger restricted-shadow/experiment.sqlite --resource-reservations resources.json --endpoint http://localhost:8080/api/eval/evolution/replay --max-runs 12 --stop-file restricted-shadow/STOP --output restricted-shadow/runs-001
```

Use the **existing shared experiment ledger**, located inside the authorized restricted store from the start of the experiment; never create a fresh ledger to reset earlier search/holdout spend. Both the result directory and ledger must resolve inside that store. The fixed ledger admits every analyst/final-answer call before submission, alternates the two arms, counts failed attempts, preserves complete responses and never retries ambiguous submissions. Re-running into a new output directory restores the same recorded run IDs without model calls. Budget exhaustion, stop files or expired authorization stop subsequent submissions. SHADOW cannot use the unpaired CLI path. The endpoint must match the signed authorization, use HTTPS or loopback HTTP, and cannot redirect the administrative token to another endpoint. Runtime authorization is rechecked before each invocation.

All source snapshots, plans, execution files, raw reports, invocation proofs and the ledger containing responses belong in restricted evaluation storage. `retentionUntil` is the reviewed retention deadline; new execution and acceptance are rejected after it. The storage owner must apply the reviewed purge/retention procedure to every raw copy, including ledger response/proof payloads, while preserving non-content accounting and signed audit metadata. These commands do not automatically delete files or erase the shared spending history. Verify the actual storage permissions and retention process in the operational proof; a path check is not a filesystem ACL. Normal serving observability must receive only version/run/outcome/usage metadata, not these raw artifacts.

Run the existing source/gold quality, blind-adjudication and resource-review steps on both shadow reports. Keep their complete original opinions and all failed runs. When invoking `evolution_compare.py`, additionally supply the same `--shadow-authorization` and `--approved-artifact`. SHADOW comparisons verify approval identities, runtime conditions, authorization timing and report binding, while reusing the existing independent comparison gates. They always have `eligible: false`. Signed `shadowChecksPassed: true` means those preregistered checks passed; a shadow batch need not independently rediscover the earlier holdout's improvement effect.

The final independent review JSON has exactly `comparisonEvidenceSha256`, `reviewer`, `reviewedAt`, and `checks`. Bind `comparisonEvidenceSha256` to the comparison's `signedReleaseEvidence.payloadSha256`. Required proof-backed checks are `noBusinessWrites`, `baselineOnlyDelivery`, `overloadIsolation`, `privacyRetention`, and `deploymentCompatibility`; each has the same `status / evidenceSha256` structure. Proofs should establish unchanged formal conversation/report/memory stores, baseline-only normal responses, observed overload/timeout separation, actual storage/retention controls and compatible deployment conditions, rather than relabeling unit tests as live evidence.

```powershell
python evolution_shadow.py --mode accept --approved-artifact approved-artifact.json --authorization restricted-shadow/authorized/shadow-authorization.json --comparison-result shadow-comparison.json --review shadow-acceptance-review.json --evidence-index shadow-acceptance-proofs.json --output restricted-shadow/acceptance
```

This emits signed `SHADOW_ACCEPTANCE / ACCEPTED_FOR_ROLLBACK_DRILL`. It verifies the signed comparison and original proof bytes, requires acceptance within the authorized retention period, and cannot authorize activation. Preparation, authorization and acceptance outputs must use new directories. Exit `0` means the requested artifact was written, `2` means an invalid or incomplete workflow input; batch replay retains its existing execution/budget exit codes. Actual deployment evidence and remaining rollback/activation work are tracked in [progress.md](../../progress.md).

### Iteration record and evidence index

[`evolution_iteration.py`](../evolution_iteration.py) writes a new immutable directory containing `<iterationId>.md` (for example `iteration-001.md`) and `artifact-index.json`. It records engineering evidence, component/final-answer primary-metric decisions, the overall independent comparison outcome, lineage, unresolved issues and the next manually triggered batch. Primary-metric gains do not override failed hard gates or an inconclusive overall comparison. It never generates candidates, runs models, activates a method or schedules the next iteration.

```powershell
python evolution_iteration.py --manifest iteration-input.json --output restricted-eval/iterations/iteration-001
```

The manifest has exactly `schema: fundamentals_iteration_manifest_v1`, `iterationId`, `actor`, timezone-aware `recordedAt`, `artifacts`, `proofs`, `unresolved`, `nextBatch`, and `closure`. Every `artifacts` value is `{"path":"relative-or-absolute-file","sha256":"<original-file-hash>"}`; paths resolve relative to the manifest. `proofs` maps original SHA-256 to file paths. All referenced bytes are verified before reporting. Source/gold inputs can use their original `.jsonl` files. Keep real source material, reviewer opinions, account scopes and operational proofs in restricted storage; the report's index links them without copying their raw contents.

| Artifact role | Binding |
|---|---|
| `gate`, `sourceCases`, `gold`, `baselineReport` | The current evaluator's signed E07 gate and its exact original inputs. |
| `candidate`, `registry` | Candidate/experience identities, parent methods/experiences, registry events, and original development traces with independently signed feedback supplied through `proofs`. |
| `validation`, `validationManifest`, `validationBaseline`, `validationCandidate` | Signed comparison plus its original plan and both execution reports. |
| `selection`, `holdout`, `holdoutManifest`, `holdoutBaseline`, `holdoutCandidate` | At most one selected validation candidate and the matching independent final comparison inputs. |
| `build`, `approved`, `shadow`, `drill`, `rollback`, `activation` | Reconstructed accepted package and linked signed publication, shadow, drill, rollback and serving records. |
| `execution`, `qualityReviews`, `operationalReviews` | Actual ordinary serving export and reviews; observations are recomputed, not copied from a previous summary. |
| `offlineDrill` | Original offline producer manifest, rechecked through the shared raw-run validator; it can establish only `OFFLINE_ORDINARY_CHAIN_VERIFIED`. |
| `search` | Optional full batch-search result for the artifact inventory, without granting evaluation authority. |

Current V2 comparisons sign `comparisonReportSha256`, calculated over the complete comparison object except `releaseEvidence` and `signedReleaseEvidence`. The common `verified_comparison` checker authenticates that binding before selection, shadow acceptance or iteration reporting uses the reported results. Changing metrics, stage decisions, exclusions or overall status invalidates the report. Historical reports without this binding must be independently recomputed under the current evaluator; do not fill in the hash by hand.

Each unresolved item contains `issueType`, `description` and indexed `evidenceRefs`. The existing failure-attribution mapping directs method errors to candidates and data/capability/executor/provider/evaluator issues to their respective development queues. `nextBatch` contains only `trigger: MANUAL` and a concrete `scope`; future batches still require registered budgets, independent evaluation and manual publication. Reusing consumed holdout material as development evidence requires a new independent holdout.

Use `closure: null` while evidence is incomplete. Such a record stays `OPEN`, and missing real results remain `UNVERIFIED`; generating the report is not plan completion. A requested closure contains `actor`, timezone-aware `at`, `decision`, `reason`, and proof-backed `checks` for `lineage`, `engineeringLoop` and `runtimeEndState`, using the existing `status / evidenceSha256` format. These are reviewed operational attestations, not facts established merely by their syntax. The report verifies them against original indexed bytes and rejects future closure dates.

`CLOSED_BASELINE_RETAINED` requires a bound independent `NO_IMPROVEMENT` result. `CLOSED_INCONCLUSIVE` requires a bound `INCONCLUSIVE` result. Either may end at validation without selecting a candidate or consuming holdout; both still require the real accepted baseline, complete development lineage, original evaluation inputs and reviewed stable runtime evidence. `CLOSED_RELEASED` additionally requires accepted improved holdout, the complete linked deployment chain and recomputed serving observations containing actual candidate requests without failed/unknown execution or review checks. It does not itself authorize a release or expansion. Engineering completion is scoped to this evidenced iteration, not every route or the entire repository.

The scope contract is [here](../../docs/evolution/evolution-v1-contract.md). Actual acceptance, deferred work and next steps are maintained only in [progress.md](../../progress.md).
