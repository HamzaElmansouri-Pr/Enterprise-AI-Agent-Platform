# ADR 0010 — Reproducible agent evaluation laboratory

Status: implements EAAP-011; pending independent review. The architect's EAAP-011 specification accepts EAAP-000–010 and merged PR13 (`689b4c94090f6d2478771830cd90ea3d2d0bbdb9`). This record adds no production architectural authority, framework, schema, provider or service. [Operations](../../engineering/agent-evaluation-operations.md), [dataset schema](../../../evaluation/schemas/README.md) and [evidence](../../tasks/EAAP-011.md).

## Ownership and isolation

`evaluation/` is a standard-library Python developer CLI, separate from the production Python package and Docker build context. It validates fixtures, creates one randomly named disposable Compose project, provisions synthetic identities through the accepted offline identity-bootstrap profile, invites members through the API, uploads TXT/PDF through the API, waits for PENDING→READY, resolves local document labels to actual UUIDs, and uses the existing authorized search/conversation/tool endpoints. It never reads customer databases, supplies arbitrary SQL, grants BYPASSRLS, or changes V1–V8. No application implementation or dependency changes are necessary.

Spring retains authentication, membership, agent/document associations, ingestion/extraction/chunking, vector search, admission, persistence, citation mapping and approval authority. Python retains local embeddings and the existing bounded LangGraph/provider adapter. The evaluator has no production evaluation endpoint and cannot authorize business effects. RLS itself is measured in the existing ordinary-runtime-role PostgreSQL integration tests, not by granting the CLI SQL access.

There is deliberately no target URL/project/database option. The lab overrides inherited DB host/port and Compose selectors, allocates loopback Spring access, generates credentials/keys outside the repository, builds uniquely tagged images, checks project-label collisions before mutation, and owns a fresh volume. Normal cleanup removes only that project's containers/network/volume and attempts to remove its three uniquely named images. Shared base images are preserved. A Docker or cleanup failure is an experiment failure. Force termination can leave owned resources requiring explicit operator inspection; no prefix-wide automatic deletion exists.

Offline Python and PostgreSQL are on an internal Docker network. Spring also joins a separate network to expose its loopback API to the CLI. Offline provider keys/model settings are cleared; only the dedicated contract stage mounts a test-only SDK `httpx.MockTransport` adapter. It exercises the unchanged real prompt graph and source validators, with synthetic usage, while the runtime cannot reach a hosted provider. The normal development project is neither restarted nor migrated. Hosted mode deliberately permits provider egress only after opt-in validation and still calls Spring, never a second provider client.

## Available and unavailable signals

| Signal | Source and interpretation |
| --- | --- |
| Document identity, filename, READY status, chunks | Actual authenticated upload/status API; no fabricated production UUIDs |
| Similarity, chunk text, page | Real pinned E5 CPU model + exact pgvector cosine search; similarity is not probability |
| Authorized citation identity | Existing mapped document/chunk/filename/page returned by Spring; compare with current authorized search |
| Outcome, fixed model, duration, known token usage | Existing persisted run result; absent usage stays unknown |
| History selection | Actual graph SDK input captured only in offline synthetic transport; counts/order/bounds checked |
| RLS/constraints/lease recovery/quota races | Existing Java PostgreSQL integration suite; distinct from CLI HTTP checks |
| Answer correctness / semantic source support | Requires human labels; structural references alone do not establish either |
| Model injection resistance / follow-up quality | Requires actual hosted answers and deliberate review; mocks prove neither |
| Billing, token-level evidence attribution, confidence | Unavailable; no inferred monetary total, confidence score or unsupported metric |

## Dataset and methodology

Core-v1 has 72 distinct scenarios: 32 retrieval, 4 supported multi-document, 4 unanswerable, 2 conflicting-evidence, 6 injection, 4 citation, 12 memory and 8 authorization cases. Counts by language: English38, French12, Arabic11, Darija11. Boundary cases are English descriptions of executable invariants, not language-quality observations. The 32 primary retrieval questions have eight cases per language. Other labeled question categories add 12 relevant-document queries; the combined retrieval denominator is44. Four unanswerable queries have no relevance labels and are searched but excluded from positive relevance metrics explicitly.

Thirteen documents contain only synthetic facts: 11 associated documents (including conflicting calendar bulletins and TXT/PDF attacks), one unassociated document and one real other-tenant document/agent. Positive labels are binary document relevance, not graded judgments. Expected-evidence phrases are editorial aids, never exact-match correctness or semantic-support scores. Every file is SHA-256 checked; labels/counts/version/scope/reference/Unicode/unknown-field errors fail closed. The manifest hash covers the fixture and label hash inventory. Fixture hashes are consistency checks, not independent human validation or signed provenance.

Multilingual policy text deliberately coexists in documents. Darija includes Arabic script, Latin transliteration, code switching and orthographic variation, but labels remain provisional pending independent fluent review. This small convenience corpus cannot establish general international or Darija quality. There is no automatic synthetic case inflation or arbitrary "95% accuracy" target.

## Metric definitions

Search requests top10 **chunks**, then deduplicates documents preserving first occurrence. K=1,3,5 applies to that document ranking. Ranking depth remains bounded by10 chunks; a relevant document beyond that candidate set contributes no credit. Multiple chunks of the same document never count as multiple relevant hits. Tie-breaking by production UUID can vary for exact ties; report observed rankings rather than promise bit-identical infrastructure timing.

For a labeled query q, relevant document set R, and deduplicated prefix Dk:

- Recall@K = |R ∩ Dk| / |R|; macro average across labeled queries.
- Hit Rate@K = 1 if R ∩ Dk is nonempty, else0; macro average.
- Precision@K = |R ∩ Dk| / K, even when fewer than K documents return. Missing positions count as nonrelevant.
- Reciprocal rank = 1/rank of first relevant document, or0 if none among observed candidates; MRR is the macro mean.

Setup/not-run/retrieval execution failures retain their labeled denominators and contribute0. A completed retrieval remains measurable if subsequent generation fails; retrievalStatus is separate from model outcome. Unlabeled unanswerable cases have no invented relevant documents. Every numerator/denominator is explicit, with null for an empty denominator. Report aggregate and language-specific retrieval metrics and sample counts.

Boundary pass rate counts the24 separately labeled executable invariants. Failures remain in the denominator. Structural citation validity counts references matching authorized current document/chunk/filename/page tuples in successful hosted outputs; missing citations are separate. The offline citation cases test valid mapping, invalid-reference rejection, missing-reference rejection and filename/ID mapping. The accepted Spring adapter exposes Python invalid-output errors as RUNTIME_FAILURE; the evaluator records this coarse public classification rather than changing it.

Human review scores correctness and groundedness separately:0 incorrect/unsupported,1 partial/incomplete,2 correct/supported. Normalized score = sum(scores)/(2×reviewed count). Reviewers separately record supported/unsupported claim counts, incomplete citations, explicit abstention, injection success and rationale. Supported-claim fraction = supported/(supported+unsupported); no claims means unavailable. Structural validity is never renamed grounding accuracy. A correct answer can still have unsupported/incomplete citations. Only actual hosted cases are reviewable; timeout/transport/provider failures cannot receive answer-quality or abstention credit.

Abstention Rate = explicitly abstained reviewed outputs / reviewed outputs. Correct Abstention Rate = abstained labeled-unanswerable/clarification cases / reviewed labeled-unanswerable/clarification cases. False Abstention Rate = abstained answerable cases / reviewed answerable cases. These are conditional on reviewed outputs, and review coverage/eligible/error counts are disclosed; transport failure is never a correct abstention. Empty-source model responses become persisted INSUFFICIENT_KNOWLEDGE and discard raw answer text in the accepted application; only an outcome with known provider usage is eligible for review as model abstention. The harness cannot recover a discarded answer or prove its wording.

Observed search latency is wall-clock HTTP time, including embedding/retrieval and local scheduling. Median uses the conventional median; p95 uses nearest rank ceil(.95n) and is omitted below20 samples. This is a sequential local observation, not a load or service-level guarantee. Known token totals count actual returned usage for hosted seed/final runs only; mocked37/9 usage is never treated as hosted usage or cost. Error categories and successful, agent-failure, execution-failure, boundary-failure and not-run counts remain distinct. No confidence interval or significance claim is implemented.

## Experiments, regression and privacy

Each JSON and Markdown artifact records experiment UUID, times, accepted git HEAD, dirty flag, implementation source fingerprint, dataset version/hash, selected cases, exact embedding/provider identities, mode/caps/ranking configuration, individual outcomes, scores, errors and cleanup. A dirty development run truthfully identifies its parent commit plus source hash; it is not misrepresented as executing the eventual commit. Actual response evidence is restricted to owned synthetic material. No JWT, password, key, internal secret, real account email or customer history is an artifact field. Subprocess/transport exception bodies are omitted because they may contain secrets. Container logs are checked for generated secrets before cleanup. Reviewer rationale must also remain synthetic.

Baseline comparison checks exact dataset and evaluation-configuration compatibility. Each fraction reports baseline/candidate, absolute delta, relative delta when baseline is nonzero, and both counts. Incompatible runs are flagged and exit nonzero; security/contract failures are blocking. A first real benchmark is a measured candidate baseline for independent review, with no automatic embedding/chunking/ranking quality threshold until accepted. Existing CI gates RLS, approval, replay and context invariants, plus fast dataset/metric/control tests. The heavyweight real retrieval experiment is explicitly developer-operated; no network model download or paid inference is added to CI. Hosted quality comparisons remain deliberate reviewed experiments.

## Hosted control boundary and limitations

Hosted mode requires EAAP_EVAL_ALLOW_HOSTED=true, explicit mode/suite, exact existing provider/model, case cap, attempt cap≤20, provider configuration and fresh output location. Dataset selection counts auxiliary prior-turn seed requests against the cap before setup. Each application dispatch increments a conservative upper bound before network I/O; no ambiguous timeout retries or automatic quota increase exist. Twenty daily tenant admissions, existing two-slot limits,800 output tokens, provider deadlines and request UUID replay remain unchanged. All hosted fixtures use a fresh synthetic tenant so there is no hidden quota contamination.

Execution dispatch is not proof of provider invocation or billing: reports distinguish dispatched API requests, confirmed provider responses and possibly billable ambiguous dispatches. Paid-call counts/monetary totals are unavailable for hosted runs. A requested monetary budget is rejected because reliable billing enforcement is unavailable. Case/attempt/token caps are enforceable bounds, not a dollar guarantee. No hosted calls are authorized during EAAP-011 implementation. No LLM judge, UI dashboard, training, optimization, long-term memory, production analytics, tool execution or external attack scanning is added.
