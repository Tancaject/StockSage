# Evolution datasets and offline evaluation

The [batch-learning experiment](batch-learning-20261002/README.md) provides source-checked development tasks, conditional-experience generation and a combined-method comparison on new companies; executed stages and limitations are recorded in [its results](batch-learning-20261002/RESULTS.md). [`evolution_batch.py`](../evolution_batch.py) exports a diagnostic bundle with ordered member identities; it does not extend the single-experience formal release contract. [`evolution_ai_review.py`](../evolution_ai_review.py) is an experimental adapter for native analysis/final stages under the fundamentals rubric and keeps AI gold `UNREVIEWED`; ordinary-answer calibration does not qualify this adapter, and its results do not grant release approval.

Protocol repairs, thinking-budget comparisons and their frozen evidence are recorded in [the repair results](batch-repair-20261002/RESULTS.md). Technical review validity, agreement with independent source review and business answer quality are reported separately.

The [subsequent batch-learning validation](batch-learning-validation-20261002/RESULTS.md) records complete-draft replay, independently confirmed method failures, actual experience generation and candidate screening. Its registered calls, AI source reviews and transfer decision remain separate from formal release acceptance.

The [learning-revision diagnostic](learning-revision-20261003/RESULTS.md) compares an overbroad experience with a model-generated revision. [`evolution_learning_input.py`](../evolution_learning_input.py) keeps verified analysis answers, evidence and feedback in model-facing requests while excluding duplicated prompts and final-stage traces; original audit artifacts remain intact. This development-only analysis replay does not select or activate an experience.

`synthetic-smoke/` contains 12 **SYNTHETIC** cases and separately stored, generated fact records. They exercise data plumbing and deterministic comparisons only. They are not real company research, independently reviewed labels, a protected holdout, or evidence of improved research quality. Research-set acceptance is tracked in [progress.md](../../progress.md).

The schema authority is [`../evolution_dataset.py`](../evolution_dataset.py). `cases.jsonl` contains exact case IDs, source snapshots, report groups, dates, and hashes; `gold.jsonl` binds each record to a case ID and canonical case hash. The fundamentals-specific criteria are owned by [`../evolution_rubric.py`](../evolution_rubric.py), with separate analysis and final-answer requirements. The stable dimension keys are `claim_support`, `numeric_period_correctness`, `counterevidence`, and `unknowns`; synthetic labels remain `NO_DATA` with `humanReviewStatus: UNREVIEWED`. Shared review-format and evidence-binding checks in `eval_common.py` do not assign semantic ratings. New reviews bind the actual stage scope and rubric hash; reviews created for a different rubric require reassessment, not relabeling.

The 12 cases cover normal values, zero, negative values, missing values, reporting periods, units, currencies, restatements, issuer mismatch, old evidence, document prompt injection, and an omitted raw-only appendix. Wrong-issuer and old evidence are intentionally accepted inputs: validating the dataset does not certify that such evidence supports the requested answer. The injection fixture tests transport of untrusted content; its existence does not demonstrate successful injection resistance.

`execution_manifest()` creates an allowlisted `{schemaVersion, cases, runs}` document. Version 2 additionally registers `context` with `experimentId`, `evaluatorVersion` and `runMode`; signed E07 and paired evaluation require this identity. Each execution case carries the original `query`, separate `resolvedQuery`, user/assistant-only `history`, ISO local-date `asOf`, source `context`, and hashes. Synthetic context is exactly the ordered concatenation of `[E#] ` plus each `visibleText`, separated by two newlines. Raw-only text and gold are excluded. Synthetic source metadata required during execution is present in visible text itself.

The explicit synthetic profile is `executionScope: SYNTHETIC_COMPONENT_CHAIN`, `ticker: issuerId`, empty `requestAttributes`, `timeSensitivity: HISTORICAL`, and `initialTaskOutcome: DEGRADED`. These are fixture inputs included in the case hash, not observed production outcomes or values derived from gold. `preAnalystContext` is a snapshot of the production evidence-prefix assembly, bound by its own hash; the Java loader rebuilds it with `ToolPrefetchService.ordinaryEvidenceContext` to detect drift. `citationIds` preserves evidence order. The smoke cases have empty history and no RAG or history-memory inputs; they do not reproduce all production inputs.

Each manifest export creates fresh UUID run IDs with explicit `caseId`, `bundleId: baseline-v1`, and `repeatId: 1`. The run plan does not change the stable case hash. The checked-in `execution.json` is one registration manifest; exporting another creates a distinct run plan. This is not a claim of durable exactly-once execution or experimental quality gain.

Raw/visible SHA-256 values hash the exact UTF-8 text. `case_hash()` hashes the complete case as compact JSON with sorted keys, `ensure_ascii=False`, and no final newline; Java may echo this hash without reserializing the case. `contextSha256` hashes the actual exported context. These hashes bind bytes and provenance, not truth or trusted origin.

`validate_cases()` rejects cross-split reuse of a leakage group, issuer/report-family pair, source reference, raw hash, or visible hash, and rejects duplicate case IDs. Paraphrases, overlapping excerpts with different hashes, and unregistered related reports still require explicit grouping and independent review; this validator does not perform semantic deduplication. Keep a report family, its restatements, and its query variants in the same group. All supplied fixtures are in `SMOKE` and must not be promoted into a research holdout.

`compare_fact_records()` compares separately supplied structured records with exact decimal values, preserving units, currencies, periods, and null-versus-zero. It does not parse free-form answers, convert currencies or units, derive financial formulas, or assign a semantic score. Matching records produce `MECHANISM_PASS` with `answer_quality: NO_DATA`; empty comparisons produce `NO_DATA`. Source facts from an irrelevant issuer or obsolete report do not become accepted answer claims merely by matching.

Run from `evals` with Python (standard library only):

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

An explicitly unsupported numerical claim (`support.status: FAIL`) may leave `evidenceExcerpt` empty when no supporting source exists; its answer excerpt must still match the output and its judgment remains `FAIL`. Passing claims require a source excerpt, and every nonempty source excerpt must belong to the identified evidence. The AI adapter resolves registered line IDs into these excerpts; nonnumerical causes and contextual claims belong to the semantic dimensions, not invented numerical records.

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

From `evals`, after the dedicated service and its admin token are configured:

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
python evals/evolution_build.py --output stocksage-backend/target/evolution-build
```

The output directory must be new. The builder packages offline using the existing Maven cache and the cached wrapper after verifying its versioned checksum. It excludes local application profiles and environment files even if they were added to Git. It emits `stocksage-backend.jar`, `build.json` and `build.log`; compilation failure emits no accepted manifest. The source state is explicitly `WORKTREE_SNAPSHOT`: `gitSha` identifies the base commit, while `sourceTreeSha256` binds the copied inputs, including uncommitted changes. The JAR hash covers packaged classes, resources and dependency bytes. This does not certify the compiler, machine or human review.

Export the baseline execution file from `evals`:

```powershell
python evolution_dataset.py source-cases.jsonl --gold independent-gold.jsonl --export-execution baseline-execution.json --experiment-id <frozen-experiment-id> --run-mode BASELINE
```

Use `DEVELOPMENT`, `VALIDATION` or `HOLDOUT` for the corresponding predeclared run. The exporter records the evaluator source fingerprint automatically. Launch the packaged JAR with the registered case file and matching `stocksage.evolution.eval.build-manifest`; a class-directory launch cannot satisfy this build contract. Java checks the actual startup artifact hash against the manifest before replay. Results bind the registered experiment/evaluator/mode, Git/build identity, run/repeat IDs, method bundle, model configuration, evidence, history, memory and fixed final prompt. Python E07/comparison rejects changed or missing bindings. Runtime provenance is a prerequisite for acceptance, not evidence of answer quality or environmental isolation.

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

## Approval, rollout and withdrawal

Serving uses one unsigned, content-pinned package per deployment. Governance is the experience registry state machine (`PROPOSED → VALIDATED → EVALUATED → APPROVED`), the package content hash, and a human approval record.

```powershell
python evolution_release.py --mode prepare --input release-input.json --output release-prepared
python evolution_release.py --mode approve --input release-prepared/package.json --approval approval.json --output release-approved
```

`release-input.json` maps `candidate`, `registry`, `gate` and `releaseEvidence` to files. Preparation still requires accepted `HOLDOUT` evidence with `comparisonStatus: IMPROVED` under the current evaluator, an `APPROVED` experience bound to that exact candidate and gate, and scope tags that exist in the shared [tag catalog](../../stocksage-backend/src/main/resources/evolution/tag-catalog.json). It writes `package.json` (`kind: APPROVED_METHOD`, `schemaVersion: 2`) and an `approval-template.json`. Fill `approver`, `approvedAt` and `reason`; approval writes `approved-method.json` and prints its SHA-256.

The package's `expectedConditions` keep only `modelConfigSha256` and `fixedFinalPromptSha256`. The fixed analyst contract is already bound by the bundle hash. Memory, RAG and build hashes are not serving conditions: they differ on every real request, so binding them made approved methods unselectable.

| Backend setting | Meaning |
|---|---|
| `stocksage.evolution.fundamentals.approved-artifact` / `-sha256` | Path to `approved-method.json` and its printed SHA-256. A mismatch, unknown tag, non-baseline parent or revoked ID fails startup. |
| `stocksage.evolution.fundamentals.rollout` | `OFF` (baseline only), `ALLOWLIST` (only `rollout-accounts`), or `ALL`. Non-`OFF` requires a package and a control directory. |
| `stocksage.evolution.fundamentals.revoked-bundle-ids` | Bundles refused at startup. |
| `stocksage.evolution.control-directory` | An empty `STABLE_ONLY` or `REVOKE.<bundleId>` file returns new requests to baseline without restart. Withdrawal is latched in the process; keep the marker or add the ID to the revoked list before restarting. |

Each ordinary FUNDAMENTALS request records `methodSelection` in its `Ordinary Evidence` trace step: the reason (`NO_APPROVED_METHOD`, `ROLLOUT_OFF`, `ACCOUNT_OUTSIDE_ROLLOUT`, `CONTROL_UNAVAILABLE`, `BUNDLE_WITHDRAWN`, `REQUEST_OUTSIDE_SCOPE`, `REQUEST_CONDITIONS_UNAVAILABLE`, `RUNTIME_CONDITIONS_CHANGED` or `APPROVED_SCOPE`), the rollout mode, the selected bundle and the derived task/evidence tags. The method object is pinned before the analyst call, so a withdrawal never changes a request already in flight.

## Production failure pool

Learning material now comes from real use, not only frozen cases. The backend writes candidate failures to `evolution_failure_cases`:

| Source | When |
|---|---|
| `USER_FEEDBACK` | The user clicks "答案有问题" on an answer (`POST /api/chat/feedback`, own traces only). |
| `ORDINARY_OUTCOME` | An ordinary FUNDAMENTALS answer ends `BLOCKED`, `DEGRADED` or `FAILED`. |
| `REPORT_REVIEW` | A DEEP report is reviewed `REJECTED` or `NEEDS_RESEARCH`; the review comment is the note. |

Ordinary FUNDAMENTALS traces now keep the exact analyst query and evidence context (`analystQuery`, `analystContext` in the `Ordinary Evidence` step; MySQL only, not exported to Phoenix), so a failure can be turned into a replay case.

```powershell
python evolution_failure_pool.py list --status NEW
python evolution_failure_pool.py show --id 12
python evolution_failure_pool.py triage --id 12 --type METHOD --note "quarter compared with half-year cumulative"
python evolution_failure_pool.py export --output evolution/production-failures-YYYYMMDD
```

Triage types: `METHOD` (enters learning), `DETERMINISTIC` (unit/arithmetic errors: fix in code), `DATA_GAP`, `PROVIDER`, `NOT_A_FAILURE`. `export` writes `case-drafts.jsonl` and `answer-key-drafts.jsonl` for METHOD failures only. A draft is not a registered case: a reviewer adds source provenance and gold, registers it with `evolution_dataset.py`, replays the baseline, and only then runs reflection.

## Deterministic numeric checks, completion gate and failure-repro cohort

[`evolution_numeric_check.py`](../evolution_numeric_check.py) checks the answer text directly, with no reviewer extraction: whether each gold value is stated (百万/亿/万亿/million/billion, percent for ratios, judged at the displayed precision), whether stated conversions such as "0.3亿美元，即约300百万美元" agree, and whether stated arithmetic such as "978 / 1,127 = 86.79%" is right. `evolution_quality.assess_claims` uses it for the `numeric` metric and adds `REQUIRED_FACT_NOT_IN_ANSWER`, `UNIT_CONVERSION_MISMATCH` and `ARITHMETIC_MISMATCH` failures. Reviewed claim extraction still owns period, unit and citation binding. Calibration on the 80 stored answer stages with gold: 461/463 gold values found (the 2 misses are the known IBM 1.93 error), and the 3 flagged issues are the known IBM 亿/百万 errors and the JNJ "14,5 亿" typo; no false positives.

`decide_comparison` now fails a candidate with `COMPLETION_RATE_REGRESSED` when it completes fewer two-stage runs than the baseline, and reports `completion`. A slower or truncating candidate no longer hides as `INCONCLUSIVE`.

If a cohort declares a key group named `failure-repro`, the primary decision uses that group only (`primary_cohort` in the result); every group, including a `regression` group of unrelated cases, still has to pass the non-regression margins. Put registered production-failure cases in `failure-repro`.

The earlier publisher-signed packages, `SHADOW` replay, drill/serving activation records, serving observation and iteration records are archived with their documentation in [archive/evolution-v1-governance](../archive/evolution-v1-governance/README.md). Historical results under this directory are unchanged.

The scope contract is [here](../../docs/evolution/evolution-v1-contract.md). Actual acceptance, deferred work and next steps are maintained only in [progress.md](../../progress.md).

The [unit-generalization combined two-stage diagnostic](unit-generalization-network-20261004/RESULTS.md) records a generated generic unit lesson, fixed-message analysis/final replay, source-bound AI reviews and the net-quality decision. It remains separate from calibrated evaluator accuracy and online activation.

The [scoped repeatability diagnostic](scoped-repeatability-20261004/RESULTS.md) adds [executable offline applicability selection](../evolution_applicability.py), rejects conditions without registered observations, and compares unchanged lesson operations on repeated DEV tasks. Pre-answer source annotations, engineering scope revisions and real output quality are reported separately; this does not implement production condition extraction.
