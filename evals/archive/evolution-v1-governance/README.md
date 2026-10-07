# Archived: evolution v1 serving governance

These scripts implemented the v1 path from an accepted candidate to serving: publisher-signed (HMAC) packages, isolated `SHADOW` replay, signed `METHOD_ACTIVATION` drill/serving records, serving observation, and iteration evidence records. They were archived on 2026-10-05 when serving governance was simplified to registry state + content hash + human approval + backend rollout settings (see [the current flow](../../evolution/README.md#approval-rollout-and-withdrawal)).

They are kept so historical records (for example the 2026-09 offline drill and `docs/evolution/iterations/`) remain explainable. They are not runnable entry points: they import publisher-signing helpers that `evolution_release.py` no longer provides, and the backend no longer reads their outputs.

| File | Former role |
|---|---|
| `evolution_shadow.py` | Prepare/authorize/accept a bounded SHADOW batch of a publisher-approved package |
| `evolution_deployment.py` | Sign DRILL/SERVING activation records and accept rollback drills |
| `evolution_observation.py` | Observe a serving batch and write withdrawal markers |
| `evolution_iteration.py` | Write an evidence-bound iteration record |
| `check_evolution_drill_exports.py` | Re-verify Java offline drill exports |

The original documentation of these steps follows, unchanged.

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

The historical `OrdinaryEvolutionDrillTest` ran four requests through actual `ChatService`, prefetch, analyst, final-answer client, prompt assembler and Trace serialization. Its model, routing and persistence boundaries used local test doubles. It withdrew during the second analyst call, checked that call's pinned candidate, then verified two fresh baseline analyst/final calls. Each run's raw Trace, response events, identity and withdrawal time were saved under a new `target/evolution-drills/ordinary-*` directory; the producer printed that exact path. The test driver has been removed; current replay limits are described in [the evaluation entrypoint](../README.md).

Historical commands from `stocksage-backend` (the removed producer cannot currently be rerun):

```powershell
$evaluatorHash = python -c "import sys; sys.path.insert(0, '../evals'); from evolution_acceptance import evaluator_hash; print(evaluator_hash())"
.\mvnw.cmd -q '-Dtest=OrdinaryEvolutionDrillTest' "-Devolution.evaluator-sha256=$evaluatorHash" test
python ..\evals\check_evolution_drill_exports.py --input <printed-directory>/offline-drill.json --output <printed-directory>/verification.json
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

The verifier reads actual `/api/trace/{traceId}` exports, accepting `steps` as the API's JSON string or a decoded array. It requires completed ordinary fundamentals analysis and a new final-answer invocation in each run. The `ordinary-evidence` step must carry its selected `methodBundle`, `analystCompletedAt`, and `methodSelection` with `authorizationSha256`, `mode`, `pinnedAt`, `caseSha256`, `comparisonIdentity`, and `reason`. Its `analystInvocation` must bind the actual system/user prompt hashes and executed method. The final-answer capture must pass the shared text-prompt/source-evidence completeness and hash checks in `eval_common.context_errors` with the explicit `ordinary-final-answer` scope. Both candidate runs must retain the approved candidate; post-withdrawal runs must report baseline with `BUNDLE_WITHDRAWN`. The in-flight run must straddle the withdrawal instant; before/after runs must occur on their respective sides. All four exports must bind the same fixed question/evidence and distinct run IDs within the authorized accounts and time window. Missing trace fields cannot be replaced with a human PASS assertion.

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

