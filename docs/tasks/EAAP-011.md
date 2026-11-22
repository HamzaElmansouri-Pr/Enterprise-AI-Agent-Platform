# EAAP-011 — Agent quality evaluation, safety and regression benchmarks

Status: engineering implementation pending independent review. EAAP-000–010 are accepted by the architect's supplied specification. PR13 is independently verified merged at `689b4c94090f6d2478771830cd90ea3d2d0bbdb9`. This branch starts from that clean accepted master. No EAAP-012, merge, force-push or paid inference is authorized here.

## Implementation and inventory

Inspection covered AGENTS.md, ADR0001–0009, engineering standards, EAAP-005 real E5 ingestion/pgvector search, EAAP-006 graph/provider/source validation, EAAP-007 approvals, EAAP-009 invitations/current membership, EAAP-010 durable creator-owned conversations/history/recovery, manifests, run/citation/usage contracts, four-service Compose overlays, unit/integration/UI tests and GitHub Actions. The architecture already supplies real identities/scores/citations/duration/nullable usage; it lacks human semantic-support labels and trustworthy billing totals. [ADR0010](../architecture/decisions/0010-agent-evaluation-framework.md) explicitly inventories those signals and limits.

Significant additions:

- `evaluation/dataset.py`, `datasets/core-v1/`, `schemas/README.md`: strict versioned schema, curated synthetic documents/cases, counts, labeling limitations and hashes; local document labels resolve to real API UUIDs.
- `evaluation/environment.py`: fresh owned Compose/volume/images, generated protected keys/passwords, accepted administrative bootstrap, real API provisioning, offline runtime egress isolation, no external target selector, safe cleanup/log-secret checks.
- `evaluation/runner.py`, `__main__.py`: explicit suites/modes/case selection/attempt controls, real authorized retrieval, deliberate hosted conversation dispatch, partial/error artifacts, provenance, caps and no paid retries.
- `evaluation/scenarios.py`, `mock_runtime.py`:24 actual boundary/history/citation invariants and16 test-only SDK invocations through the unchanged real LangGraph. No production fake-provider option.
- `evaluation/metrics.py`, `reports.py`: document-level Recall/Hit/Precision@1/3/5, MRR, explicit denominators, language groups, latency, returned usage, separate structural/semantic evaluation, human-review rubric, compatible baseline comparison.
- `evaluation/tests/`:48 deterministic tests for schema/integrity/Unicode, metric arithmetic, failed-case denominators, review provenance, timeout versus abstention, wrong-scope source rejection, no redirect/retry, opt-in/case/attempt caps, output preservation, truthful partial reports and redaction.
- `.gitattributes`: evaluation PDF fixtures are binary (their valid PDF cross-reference records contain trailing spaces).
- `.github/workflows/ci.yaml`: existing Python job also validates dataset, unit tests and Ruff for evaluation. Existing Java RLS/approval/replay/history gates remain enabled. No paid/real-model download is added to CI.
- [Operations](../engineering/agent-evaluation-operations.md), ADR0010, evaluation README/schema and root/architecture/task index links.

Production Java/Python/React implementations, all manifests/locks, Compose files and accepted migrations V1–V8 are unchanged. No new application framework, server, database, endpoint, index, cache or external integration is selected. The requested headless CLI is prioritized; optional React analytics are omitted.

## Dataset and actual measurements

72 synthetic cases: retrieval32, supported multi-document4, unanswerable4, conflicting evidence2, injection6, citation4, bounded memory12, authorization8. Language counts: English38, French12, Modern Standard Arabic11, Darija11. The32 primary retrieval questions have eight per language. All48 question cases run actual search;44 have explicit positive document-relevance labels. Twenty-four boundary descriptions do not inflate language-quality counts.

Thirteen fixtures:11 associated documents, one unassociated canary and one real other-tenant document associated with that tenant's own agent. TXT and selectable-text PDF are both ingested. The worker must actually transition202/PENDING to READY with positive chunks and the approved fixed model identity before a benchmark query is scored. No keyword replacement, fake vector, private document or invented production UUID is used.

The committed [baseline JSON](../../evaluation/reports/baseline-core-v1/results.json) and [Markdown](../../evaluation/reports/baseline-core-v1/report.md) contain real measured rankings and similarities from a successful fresh lab, not a hand-authored metric artifact. Baseline experiment `eval-3f6010114ee4443982d0b28f6ee04da7` completed72/72 cases. It records accepted HEAD plus dirty=true and its exact source fingerprint; it does not claim to have executed the eventual Git commit. This is an observed baseline proposed for independent review, not an approved arbitrary quality floor.

| Metric | @1 | @3 | @5 |
| --- | ---: | ---: | ---: |
| Macro Recall | 0.750000 | 0.943182 | 0.977273 |
| Hit Rate | 0.818182 | 0.954545 | 0.977273 |
| Precision (fixed K denominator) | 0.818182 | 0.356061 | 0.222727 |

All three use44 labeled queries; MRR=0.889646. Multiple relevant documents receive fractional recall, and duplicate chunks are deduplicated to documents before K. Fewer than K returns do not shrink precision's denominator. Unlabeled unanswerable searches have no invented relevant documents. Setup/failed/not-run labeled queries remain as0 contributions. A completed retrieval does not lose its measured ranking when generation fails.

| Language | Labeled queries | Recall@1 | Recall@3 | Recall@5 | MRR |
| --- | ---: | ---: | ---: | ---: | ---: |
| English |13|0.769231|1.000000|1.000000|0.910256|
| French |11|0.636364|0.909091|0.909091|0.828283|
| Arabic |10|0.850000|0.900000|1.000000|0.920000|
| Darija |10|0.750000|0.950000|1.000000|0.900000|

The French housing-allocation question's expected housing document ranked9, outside top5. That miss remains visible; labels are not adjusted to improve scores. This small mixed-language corpus does not justify general Darija, cross-language-transfer or production-quality claims. Labels and phrasing need independent native-speaker review. Baseline48 observed search latencies: median78.080ms, nearest-rank p95=93.761ms on this local host. These are sequential local observations, not a load benchmark; concurrent lab/build activity and tie ordering can affect repeated observations.

## Deterministic safety, contracts and quality boundaries

24/24 invariants passed: real wrong-tenant document/status/search403; MEMBER upload/manual proposal denial; different-user conversation404 including manager; unrelated/unassociated/other-tenant evidence exclusion; creator self-approval403; no unapproved effect409 and no task created; unknown tool400; mapped citation identity; invalid/missing references; six most recent complete turns, chronological order,8000-character whole-pair cap; failed-history exclusion; conversation/user/tenant isolation; terminal close; archived history; same-request replay/conflicting payload.

The real graph's synthetic SDK proof records16 invocations, history lengths `[0,1,2,3,4,5,6,6,6,6,1,2,2,2,0,0]`, maximum selected history6160 characters, and0 additional calls on replay. Two5900-character assistant outputs force whole-pair exclusion. Invalid citation becomes durableFAILED/RUNTIME_FAILURE at the existing public adapter; missing sources becomeFAILED/INSUFFICIENT_KNOWLEDGE. No unsupported answer is presented as success. Existing Java ordinary-runtime-role tests separately prove forced RLS, privileges, FK/constraints, quotas/races, stale recovery and late-response fencing; the CLI acquires no SQL authority for those checks.

Six adversarial generation cases exist for user/retrieved TXT/PDF instructions, fake system authority, invalid citations, platform disclosure, tenant-managed instructions, prior-turn claims and approval bypass. **No actual model attack-resistance result is claimed.** Offline mocks only verify contracts, selected context and trusted enforcement. Test-only token values are excluded from hosted usage metrics.

Actual hosted generation quality remains unmeasured: correctness, semantic citation support, human groundedness, model abstention and injection-success scores have unavailable denominators until deliberately authorized hosted execution and review. A valid source ID is not semantic grounding. The review workflow saves separate0/1/2 correctness/groundedness, explicit abstention, supported/unsupported claims, incomplete citations, attack success and rationale, with run/dataset identity. Timeout/provider errors cannot be scored as abstention. No LLM judge or exact-string answer oracle is used.

Hosted control tests pass with mocked application transports: default-disabled flag; explicit fixed model/provider/configuration/output and≤20 bounds; case/auxiliary-seed capacity checked before setup; dispatch counted before network I/O; no second provider client or automatic retry; unknown monetary cap rejected rather than pretending dollar enforcement. Production20/day admissions, two process slots, deadlines, idempotency and800-token output limit remain unchanged. **Paid provider calls performed:0.**

## Verification commands and evidence

Executed from services/control-plane with the existing temporary Java21/Maven setup:

```bash
JAVA_HOME=/tmp/eaap-001-jdk MAVEN_USER_HOME=/tmp/eaap-001-maven \
  ./mvnw -B -ntp -Dmaven.repo.local=/tmp/eaap-001-m2 verify
```

Result:79 unit +179 real PostgreSQL integration =258 passed;0 failures/errors/skips. Includes all accepted security/migration/approval/replay/history regressions. Existing database and key fixtures are disposable; no development volume is used.

From services/agent-runtime, using existing uv/cache setup:

```bash
uv run --frozen ruff check .
uv run --frozen ruff format --check .
uv run --frozen pytest
uv run --frozen ruff check --config ../../evaluation/ruff.toml ../../evaluation
uv run --frozen ruff format --check --config ../../evaluation/ruff.toml ../../evaluation
```

Result:106 runtime tests passed; Ruff check/format passed for runtime and evaluation. Evaluation48 standard-library tests passed via `python3 -m unittest discover -s evaluation/tests -v`. Dataset validation passed via `python3 -m evaluation validate --dataset evaluation/datasets/core-v1`. Tests need no paid credentials; local-network runtime tests ran with appropriate sandbox access.

From repository root with existing Node22 tooling:

```bash
npm --prefix apps/web run lint
npm --prefix apps/web run typecheck
npm --prefix apps/web test
npm --prefix apps/web run build
npm --prefix apps/web audit --json
```

Result: lint/types/build passed;88 frontend tests passed. Audit reported0 advisories at verification time; no dependency changed. This does not establish complete image/supply-chain security.

Real benchmark command, executed with the prepared approved E5 assets and isolated Compose access:

```bash
DOCKER_CONFIG=/tmp/eaap-001-docker-config python3 -m evaluation run \
  --dataset evaluation/datasets/core-v1 --suite all --mode offline \
  --model-dir /tmp/eaap005-e5-model --output /tmp/eaap011-run3
```

Result:72/72 complete, actual local extraction/embedding/pgvector,24/24 invariant checks,16 synthetic SDK calls,0 paid calls; owned containers/network/volume removed. All four existing services built and reached healthy state. No normal development service was restarted or migrated. Report rendering executed from actual baseline JSON. A second and final-code fresh lab also completed72/72 with24/24 invariants,16 synthetic SDK invocations and0 paid calls. The committed [repeat JSON](../../evaluation/reports/repeat-core-v1/results.json), [repeat Markdown](../../evaluation/reports/repeat-core-v1/report.md) and [comparison](../../evaluation/reports/comparison-core-v1.json) record experiment `eval-4947677891e14791b33c7bc952a2e6e2`. Comparison is compatible and reports zero absolute change for every aggregate retrieval metric, with no deterministic security regression. The runs retain their actual distinct development-source fingerprints; reporting/checkpoint/schema-validation refinements did not change the production pipeline or valid fixture labels. Artifacts were rendered from actual recorded outcomes using the final report command, not populated with invented measurements.

```bash
python3 -m evaluation report --run /tmp/eaap011-run3/results.json \
  --output evaluation/reports/baseline-core-v1
python3 -m evaluation report --run /tmp/eaap011-run5/results.json \
  --output evaluation/reports/repeat-core-v1
python3 -m evaluation compare \
  --baseline evaluation/reports/baseline-core-v1/results.json \
  --candidate evaluation/reports/repeat-core-v1/results.json \
  --output evaluation/reports/comparison-core-v1.json
```

Two earlier development attempts truthfully failed: first the initial internal-only Docker network prevented host API access before any case; second an evaluator expected INVALID_MODEL_OUTPUT while the accepted HTTP adapter deliberately reports RUNTIME_FAILURE. The lab access network and evaluator expectation were corrected; no production security boundary or accepted test was weakened. Failed artifacts remained failed, and no fake benchmark was substituted.

Remote CI also exposed working-directory-dependent Ruff classification of the test runtime imports. Added evaluation/ruff.toml extending the existing rules with explicit evaluation/agent_runtime first-party identities; the same checks pass from both repository root and runtime directory. No rule was disabled. Final reporting review also added a regression for independently nullable input/output usage: known directions are summed separately and absent usage remains null, including in re-rendered artifacts. No retrieval or hosted experiment was invented or repeated for that reporting-only correction. The final PR and delivery response record actual Git/CI URLs and status after pushing. A workflow definition or local result is not represented as a remote CI pass. No PR merge is performed.

## Security, performance, limitations and review

No private customer data, real user credentials, keys, JWTs or internal secrets belong in reports or fixtures. All identities use synthetic example.test email addresses and generated temporary credentials. Container logs are checked for generated secrets; subprocess/transport bodies are not copied into artifacts. Offline provider settings are cleared and runtime egress is denied. Redirects/external targets, SQL tools, production DB mutation, paid retries, unrestricted scans and automatic business effects are absent. Authorized privileged setup uses only existing bootstrap/migration/extension workflows on a fresh owned database.

The CLI dispatches sequential bounded searches/turns, caps response bytes/deadlines, waits with finite polling for ingestion, keeps the existing pool/resource limits and uses exact bounded top10 chunk retrieval. No index/caching/performance optimization is introduced without measurement. Artifacts include synthetic evidence only. Docker shared bases/build caches can remain; cleanup failures and force termination need explicit inspection of the exact recorded lab project.

Known limits: small convenience corpus; multilingual evidence mixes languages; provisional Darija labels; binary document relevance not graded claims; no confidence intervals/significance; no live hosted quality/billing evidence; coarse public runtime failure classification; discarded abstention wording cannot be recovered; review is human/fallible; RLS GUCs remain trusted assertions under the accepted runtime-credential-compromise limit. These do not become production guarantees.

Branch: `feat/eaap-011-agent-evaluation`. Required commit subject: `feat: establish reproducible agent evaluation and safety benchmarks`. Push destination: existing origin GitHub repository. PR targets master and remains unmerged for independent review. No unapproved deviation or new architecture decision is introduced. Next action is independent evaluation/security/label review; do not start EAAP-012 without a separately approved task.
