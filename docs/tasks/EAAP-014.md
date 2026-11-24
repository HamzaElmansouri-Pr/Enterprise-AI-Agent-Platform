# EAAP-014 — End-to-end business pilot and product acceptance

Local implementation and verification are complete; final Git/CI delivery is reported with the actual PR metadata in the delivery response. Independent product, architecture and security acceptance remains separate. EAAP-015 is not authorized.

## Baseline and implementation

Started from clean `feat/eaap-013-identity-spring-hardening`, fetched origin, verified accepted master `693d8bd60d995287fba27ef2e5dbe89b7354d374` (merged PR16) and successful master CI run `45072652469`. Fast-forwarded local master and created `feat/eaap-014-business-pilot-validation`. Origin remains `https://github.com/HamzaElmansouri-Pr/Enterprise-AI-Agent-Platform.git`; no merge, history rewrite or branch rename.

Inspected governance/ADRs 0001–0012, accepted console/access/conversation/evaluation/deployment records, actual React features/API clients, Java routes, existing temporary Chromium scripts, CI and pilot/evaluation fixture tooling. [The inventory](../../pilot/scenarios/atlas-learning-center/inventory.md) preceded UI changes.

Significant files:

- `pilot/scenarios/atlas-learning-center/`: independent `atlas-v1` fact manifest, 12 hashed PDF/TXT fixtures (11 Atlas, one Rif), 10 multilingual retrieval cases, pre-change functionality inventory and sanitized evidence.
- `pilot/environment.py`, `run.py`: fresh external config/secret provisioning, approved offline initial owners, isolated HTTPS lifecycle and owned cleanup; no development `.env` loading or arbitrary target URL.
- `pilot/runtime_contract.py`: read-only test mount using existing EAAP-011 SDK transport after real pilot startup checks, not a production provider mode or image layer.
- `pilot/browser.mjs`: actual Chromium/UI/API journeys, request replay/fault recovery, authorization failures, persistence checks, mobile/tablet/desktop captures and machine measurements.
- `pilot/dataset.py`, `report.py`, `tests/`: independently validated fixture hashes/labels, portable JSON/Markdown evidence and safety checks against reusing an existing experiment directory.
- React `FailureNotice`, knowledge, execution, task and console components: demonstrated failure guidance and bounded association-name display; safe unknown-code handling; actual upload-input reset; full selected tenant UUID; explicit internal-task meaning. The invitation input pattern now uses valid Unicode-sets syntax, verified with invalid and valid URL-safe inputs. Existing footer color changed from 3.503:1 to 5.362:1 contrast using the existing muted palette.
- ADR0013, business operations and product assessment: reproducibility, accepted boundaries, P0/P1/P2/P3 evidence and remaining product gaps. CI retains all three jobs and adds Atlas validation/unit/lint checks; Compose/Chromium remains an explicit heavy verification.

## Architecture, security and performance

No application service, migration, schema, role, provider, framework or dependency manifest changed. Spring remains trusted authorization/persistence/retrieval/tool boundary; LangGraph is bounded inference; React presents actual outcomes. V1–V8 and forced RLS remain unchanged. No staff/business rows are inserted by SQL. Only approved initial OWNER bootstrap and operational backup/preflight snapshots use operator capabilities.

Fresh synthetic Atlas/Rif tenants use `example.test` accounts and generated external credentials. No real customer information, contact delivery, paid calls or public deployment. The accepted HTTPS profile keeps non-root/read-only containers, file secrets, private API/runtime/data networks, login throttling, private diagnostics and immutable image locks. Dirty local-test images are explicitly experimental; reports include actual image IDs and source hashes, not a clean production-release claim. API clients validate the generated CA; Chromium pins its public key; the transport-fault request client trusts only the generated fixture CA in addition to ordinary system trust.

Association names use one existing bounded page (20), with explicit support-ID fallback, not N+1 requests. Existing pagination, document constraints, vector dimensions, graph/context/token/concurrency/timeouts and task uniqueness are preserved. Benchmark and browser latencies are machine measurements. No speculative caching or unbounded background retry.

## Regression verification executed

All commands ran from the root except component commands run in their own directories. Local caches/tools were external; use the documented normal equivalent on another workstation.

| Command | Actual outcome |
| --- | --- |
| `JAVA_HOME=/tmp/eaap-001-jdk MAVEN_USER_HOME=/tmp/eaap-001-maven ./mvnw -B -ntp -Dmaven.repo.local=/tmp/eaap-001-m2 verify` in control-plane | BUILD SUCCESS: 96 unit + 180 PostgreSQL integration = 276; no failures/errors/skips. Authentication, access/roles, RLS, knowledge, conversations, tools and pilot config included. |
| `uv run --locked ruff check .`, `ruff format --check .`, `pytest` in runtime | Lint/format passed; 118 tests passed, including real LangGraph synthetic-provider contracts. |
| `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` in web | Passed; 93 tests in 7 files including five new failure-guidance/privacy tests; production build passed. |
| `python3 -m evaluation validate --dataset evaluation/datasets/core-v1` | Valid accepted dataset SHA256 `97c17c81b57feb9a24e820faa2251e95cad7775a63142607ae3e01236502487b`. |
| `python3 -m unittest discover -s evaluation/tests -v` | 48 passed. |
| `python3 -m unittest discover -s deploy/tests -v`, `python3 -m deploy.scripts.check` | 18 passed, including real authenticated-crypto negatives; configuration checks passed. |
| `python3 -m deploy.scripts.ingress_smoke` | Actual Nginx TCP peer separation, spoofed-header stripping, static bypass and Retry-After checks passed. |
| `python3 -m pilot.dataset`, `python3 -m unittest discover -s pilot/tests -v` | Twelve document hashes / ten multilingual labels validated; 4 tests passed, including rejecting an existing directory before any provisioning or cleanup. |
| `ruff check --config pilot/ruff.toml pilot`, `ruff format --check --config pilot/ruff.toml pilot` | Pilot tooling lint/format verified. |

Full benchmark:

```bash
python3 -m evaluation run --dataset evaluation/datasets/core-v1 --suite all \
  --mode offline --model-dir /tmp/eaap005-e5-model --output /tmp/eaap014-core-evaluation
python3 -m evaluation compare --baseline evaluation/reports/baseline-core-v1/results.json \
  --candidate /tmp/eaap014-core-evaluation/results.json --output /tmp/eaap014-core-comparison.json
```

All 72 cases completed, 24/24 executable boundaries, 16 synthetic SDK calls and zero paid calls. Recall@1/3/5 = 0.750000/0.943182/0.977273; MRR 0.889646. Retrieval metrics unchanged against baseline; comparison compatible, no security regression. Forty-eight real search timings: median 68.931 ms, p95 86.621 ms. No hosted quality/review-score claim is made.

## Encrypted operational recovery

Ran `deploy.scripts.acceptance.drill` with new `/tmp/eaap014-recovery-source` and `/tmp/eaap014-recovery-restore` environments, ports 18543/18544 and a verified image lock. Source/restore were separate from the Atlas browser experiment. The control-plane/runtime images match the business build; recovery's web image preceded the final footer-only contrast adjustment. No database/security logic changed between those builds.

Eighteen operational checks and nine identity-ingress checks passed. AES-256-GCM/RSA-OAEP CMS archive: 154359 bytes; SHA256 `cb276b94b21397b8880251306591acffdf48a5ec573285b856dbdc59dab13688`; export 0.312 s, restore 1.286 s; complete drill 453.449 s. Fresh independent cluster restored exact counts/hashes of all 18 tables (17 business plus Flyway). Negative archive/role/nonempty-target tests, new signing-key rejection of old JWT, restored identity/knowledge/history/task replay, startup/runtime/database outage, log redaction and teardown passed. Actual ingress peer could not connect to API8080, diagnostics9090, Python8000 or PostgreSQL5432. Legitimate control logins 8/8, false throttles 0, observed expensive-hash concurrency peak 2.

These are local logical-recovery measurements, not production RTO/RPO/HA guarantees. Accepted EAAP-013 upstream Tomcat advisory applicability and lack of OS image scan/external penetration testing remain documented limitations; no dependency or security-product substitution was introduced.

## Browser, visual and business evidence

[JSON acceptance](../../pilot/scenarios/atlas-learning-center/reports/acceptance.json), [Markdown results](../../pilot/scenarios/atlas-learning-center/reports/acceptance.md) and [fact/hash manifest](../../pilot/scenarios/atlas-learning-center/manifest.json) contain the completed evidence. The final exact command was:

```bash
PATH=/tmp/eaap-001-node/bin:$PATH python3 -m pilot.run \
  --directory /tmp/eaap014-release-check --model-dir /tmp/eaap005-e5-model \
  --port 18743 --playwright-package /tmp/eaap008-browser/package.json \
  --output /tmp/eaap014-release-check-evidence
python3 -m pilot.report --browser /tmp/eaap014-release-check-evidence/browser.json \
  --output pilot/scenarios/atlas-learning-center/reports \
  --recovery /tmp/eaap014-recovery.json \
  --evaluation /tmp/eaap014-core-evaluation/results.json \
  --comparison /tmp/eaap014-core-comparison.json
```

All 32 named workflow/ingestion groups passed (individual assertions are not counted as separate tests). Both invitation forms rejected malformed token characters through actual native Chromium constraints, then accepted the generated tokens normally. Four synthetic accounts across two tenants; actual role contexts verified. OWNER creates the agent, ADMIN saves permitted configuration but cannot promote MEMBER to ADMIN, MEMBER's unauthorized writes fail. Eleven Atlas PDF/TXT documents reached READY with nonzero chunks, one corrupt PDF visibly FAILED, Rif's separate policy ingested independently. All ten Atlas queries ranked the expected document first: Recall@1/3/5 and MRR@5 = 1.0 on this small separately labeled sample. Association removal immediately excluded its source.

Persistent conversation creation, real retrieval, valid source IDs, follow-up, navigation, logout/login, rename, close and archived read-only history passed. The SDK proof recorded exactly nine synthetic calls with histories 0,1,2,3,4,5,6,6,6; maximum history 344 characters. Existing fixture assertions also enforce the 8000-character history/40000-character request bounds. Request replay and actual lost-response recovery did not cause a tenth dispatch. Invalid citation output was durably FAILED with the existing sanitized `RUNTIME_FAILURE` category and no answer/citations; production error mapping was not changed. Disabled-provider failure remained `MODEL_UNAVAILABLE` and visible.

Proposal and approval produced no task. Self/MEMBER approval and pre-approval execution failed. Distinct ADMIN approval, explicit execution, matching real OPEN task, exact one-row count, replayed same task ID and decision history passed. Rejection remained terminal. Eight cross-tenant operation categories denied access; same-tenant managers could not read another creator's conversations/runs; MEMBER self-promotion and ADMIN OWNER-power attempts failed. Current-JWT revocation, database outage/readiness recovery, invalid login and real throttling produced accurate UI outcomes.

55 explicit browser-context HTTP checks measured median 24 ms / p95 120 ms, including driver overhead; statuses: 32×200, 2×201, 16×403, 3×409, 2×404. Eleven observed upload-to-visible-READY durations: median 3457 ms, range 3446–6482 ms (includes polling). Script interaction counts are in the report; they include negative controls, repeat logins/navigation and two-tenant onboarding, not minimum clicks or human completion time. Proposal creation 7 recorded UI actions; approval/execution and supplementary manager checks remain separately observable. Exactly one persisted follow-up task was created.

All nine console areas rendered at 375/768/1440 × 1000 CSS-pixel viewports with no document-level horizontal overflow. Forty-one final screenshots were captured; representative actual images at all three widths were opened and inspected, with the long mobile conversation viewed at original resolution. [The visual assessment](../product/pilot-usability-assessment.md) lists inspected samples and remaining P2/P3 friction. Zero uncaught JavaScript/unexpected console errors; intentional negative HTTP and injected network-fault messages counted separately. No browser storage/cookies, external browser origin or secret-bearing URL/log was found. The runner removed its own containers/networks and protected browser input; synthetic volumes/operator files remain for explicit review. Development services/data were not selected or modified. Failed exploratory runs exposed automation selectors, path encoding, test-driver TLS/error-body handling and recovery-readiness waiting. The console gate also found and drove repair of the genuine invitation-pattern defect; none is counted as a passed acceptance run or misreported as an application defect.

## Delivery and product review

See [operations](../engineering/business-pilot-operations.md), [ADR0013](../architecture/decisions/0013-business-pilot-and-product-validation.md) and [product assessment](../product/pilot-usability-assessment.md). Required follow-on decisions include guided tenant onboarding/identification, human usability observation, privacy operations and a separately approved bounded hosted-model quality experiment. No EAAP-015 implementation started. Git/PR/CI evidence is recorded after actual remote success, never inferred from local checks.
