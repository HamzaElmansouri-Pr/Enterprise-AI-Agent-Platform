# EAAP-015 — Guided onboarding and controlled pilot preparation

## Baseline audit, before implementation

Fetched origin on 2026-11-26 and rechecked on 2026-11-27. Latest master is
`cd7b8569168f4035e990a578acc4da8f08d8bbe3`, the merge of EAAP-014 PR17.
The initial working tree was clean on the separate local-launcher branch;
`feat/eaap-015-guided-onboarding` starts directly from origin/master and does not
include that unmerged convenience change. The GitHub connector returned no
workflow runs for this master SHA; baseline CI success is not inferred.

Inspected governance, accepted ADRs, identity capabilities and bootstrap, actual
React login/selection/navigation, invitations, bounded agent/document lists,
creator-private conversation recovery, approval/execution, EAAP-014 failure
guidance and usability evidence, Atlas harness, privacy proposal, evaluation
controls and deployment/recovery implementation and tests.

Current limitations: tenant selection requires a manual UUID; context returns
only IDs/role, not a name. Runtime cannot SELECT users/memberships. Tenants FORCE
RLS; migration ownership does not bypass it. No authenticated tenant discovery,
provider availability endpoint or persisted onboarding progress exists. Dashboard
already reads bounded agent/document/proposal pages. Team refresh is manual;
upload's initial PENDING notice can outlive processing. Narrow tables scroll
internally without a discoverability hint. Existing paid-request recovery relies
on durable request UUIDs and read-only outcome retrieval, which must be preserved.

Plan: add one forward V9 read-only identity capability and exact authenticated
route, named paginated selection plus existing context verification, contextual
guidance derived from current bounded server pages, targeted feedback fixes,
design-only privacy/hosted/human research deliverables, and fresh independent
security/browser/restore/evaluation regression evidence. Development data must
not be selected, migrated, or modified by this task. No paid inference.

## Implementation and files

- `V9__authorized_tenant_discovery.sql`: one read-only migration-owned capability,
  a migration-only membership-scoped tenant SELECT policy, no identity-table grant.
  V1–V8 remain byte-identical. No business rows are rewritten.
- Identity `TenantDirectory`, `TenantDiscovery`, `JdbcTenantDirectory` and
  `TenantDiscoveryController`: typed port/use case, bounded prepared SQL and
  exact authenticated no-store `GET /api/v1/me/tenants`; configuration/security
  wiring preserves JWT/enablement checks and default denial.
- `TenantSelection`, `App`, `Console` and API contracts: named/role-bearing bounded
  picker, explicit fresh context verification, optional manual UUID fallback,
  scope cancellation, cleared acknowledgement/data on switch and secondary IDs.
- `FirstUseGuide`, `Dashboard`: contextual guidance from real first-page records,
  no fabricated complete flags, provider availability or unrequested model action.
- `Knowledge`, `Team`, `AgentSelect`: truthful accepted-upload copy, bounded
  return/focus directory refresh paused during sensitive/write UI, deliberate
  GET-only retry of failed agent lookups.
- `TableScroll`/CSS and feature tables: focusable regions with scroll guidance;
  navigation focus; citations explain filename/page inspection without inventing
  source download. FailureNotice/client static guidance preserves uncertain
  execution outcomes, role/lifecycle errors and no automatic write retry.
- `AuthenticationIT`, `TenantDiscoveryTest`, `TenantDiscoveryUpgradeIT`: positive/
  negative discovery, live disable/revocation, cross-user leakage, query forgery,
  ordinary-role privileges/context cleanup and explicit V8→V9 preservation.
  Existing persistence/test-database helpers only advance expected migration
  counts; original security/domain assertions remain.
- Frontend selection/guide/picker/recovery tests plus existing App/Team/knowledge
  tests: authoritative selection, zero/multiple memberships, expiry/cancellation,
  real-record indicators, bounded return refresh, safe failed-read retry and
  non-success guidance. Existing tests were not removed or weakened.
- `deploy/scripts/pilot.py`, `backup.py`, `acceptance.py` and backup tests:
  schema 9 startup, reviewed 8/9 archive compatibility, real manifest version and
  restored discovery/least-privilege checks. Unsupported/wrong-type versions fail
  closed; no destructive compatibility reset.
- `pilot/browser.mjs`: original 32 groups retained, added discovery/onboarding/
  switching/revocation and explicit read-only fault recovery; real APIs/browser,
  safe network diagnostics, no fabricated successful HTTP result.
- [ADR0014](../architecture/decisions/0014-guided-onboarding-and-tenant-discovery.md),
  [operations](../engineering/onboarding-and-tenant-operations.md),
  [privacy plan](../security/privacy-lifecycle-implementation-plan.md),
  [hosted plan](../product/hosted-quality-evaluation-plan.md),
  [human protocol](../product/human-usability-test-plan.md) and README/navigation/
  recovery updates document actual behavior and remaining decisions. The fixed
  H11 hostile design fixture is outside accepted atlas-v1 and never auto-ingested.

## Architecture and security

Spring remains the trusted membership, tenant access, persistence, retrieval and
tool boundary. New code stays in identity API/application/infrastructure; domain
Role remains framework-independent. No framework, dependency, provider, role,
database, public signup or autonomous operation was introduced. Python application
code, core-v1 labels and the accepted Atlas fixture manifest are unchanged.

The discovery actor comes only from the validated JWT subject, is checked enabled
in trusted code and joined with current enabled identity/membership in PostgreSQL.
Unknown/duplicate authority parameters fail 400; no account ID filter exists.
The definer uses static qualified SQL, empty search_path, revoked PUBLIC execution
and independently bounded limit/offset. FORCE RLS and runtime identity SELECT
denials remain intact. The temporary discovery assertion is restored on success;
statement failure rolls it back. Discovery is metadata, never a workspace grant.
Opening still calls context; business operations still reauthorize live access.

Custom PostgreSQL settings/actor arguments assert trusted application identity.
Compromised runtime SQL credentials could impersonate a known actor, as with
existing identity capabilities; this is explicitly not a guarantee against full
runtime or privileged-operator compromise. A concurrent revocation may happen
after a list snapshot; context and subsequent requests enforce current membership.

JWTs remain memory-only. Switching/logout abort old scopes and unmount private
views; late reads cannot populate another account/tenant. Names/source text are
ordinary React text. No role is authorized by a hidden button or model output.
Separate-manager approval, creator-private history, immutable decisions and
exactly-one internal task replay remain unchanged and covered by regressions.

## Resource behavior

Discovery returns size 1–100/default 20 with size+1 lookahead, offset≤10000 and
UUID ordering. Existing user/tenant/membership primary keys serve the joins;
one prepared bounded lookup, no COUNT or per-item HTTP/SQL calls, no cache or
speculative index. Existing enablement reads and three-second query deadline
remain bounded. The owner guide adds only one existing invitation-page read to
the overview's bounded parallel requests; MEMBER does not request invitations.
Return/focus refresh is at most once per five seconds and never retries writes.
Failed-read recovery requires an explicit button; no hidden polling loop.
Existing four-connection pool, document/graph/context/concurrency/timeout quotas,
four-service memory caps and private service networks remain unchanged.

## Component/regression commands and actual results

On 2026-11-27 the final retained runs used the approved JDK21 and Node22 copied
from the pinned images into external tool storage. `EAAP015_STATE` below denotes
`/home/hamza/.local/state/eaap-015`; substitute an equivalent external path.

| Executed command | Result |
| --- | --- |
| `JAVA_HOME=$EAAP015_STATE/jdk MAVEN_USER_HOME=$EAAP015_STATE/maven ./mvnw -B -ntp -Dmaven.repo.local=$EAAP015_STATE/m2 verify` in control-plane | BUILD SUCCESS;100 unit + 185 real PostgreSQL integration = 285, zero failures/errors/skips. |
| `uv run --project services/agent-runtime --locked ruff check services/agent-runtime`; `uv run --project services/agent-runtime --locked ruff format --check services/agent-runtime`; `.venv/bin/python -m pytest services/agent-runtime` | Lint/format passed; 118 tests passed. |
| `npm --prefix apps/web run lint`; `npm --prefix apps/web run typecheck`; `npm --prefix apps/web test`; `npm --prefix apps/web run build` under Node22 | Passed; 105 tests across 10 files; production build passed. |
| `python3 -m evaluation validate --dataset evaluation/datasets/core-v1`; `python3 -m unittest discover -s evaluation/tests -v` | Accepted dataset validated;48 unit tests passed. |
| `python3 -m pilot.dataset`; `python3 -m unittest discover -s pilot/tests -v` | Atlas manifest/hash labels validated;4 tests passed. |
| `python3 -m unittest discover -s deploy/tests -v`; `python3 -m deploy.scripts.check` | 19 tests passed, including real crypto negatives and V8/V9 manifest compatibility; source/secret/header gates passed. |
| `python3 -m deploy.scripts.ingress_smoke` | Real Nginx TCP-peer isolation, stripped forwarding spoofing, static bypass and retry hints passed. |
| For each `AREA` in `deploy`, `pilot`, `evaluation`: `uv run --project services/agent-runtime --locked ruff check --config "$AREA/ruff.toml" "$AREA"`; `uv run --project services/agent-runtime --locked ruff format --check --config "$AREA/ruff.toml" "$AREA"` | All passed. |

Full real offline core benchmark and comparison:

```sh
python3 -m evaluation run --dataset evaluation/datasets/core-v1 --suite all \
  --mode offline --model-dir "$EAAP015_STATE/e5-model" \
  --output "$EAAP015_STATE/core-evaluation"
python3 -m evaluation compare \
  --baseline evaluation/reports/baseline-core-v1/results.json \
  --candidate "$EAAP015_STATE/core-evaluation/results.json" \
  --output "$EAAP015_STATE/core-comparison.json"
```

72 cases COMPLETE, zero paid calls. Recall@1/3/5=0.750000/0.943182/0.977273;
MRR=0.889646 over44 labeled queries. Comparison compatible, securityRegression=false.
These are retrieval/contract observations, not hosted answer quality. Dataset and
runtime-provider integration remain the accepted ones.

## Real browser acceptance and visual review

```sh
PATH="$EAAP015_STATE/tools:$PATH" python3 -m pilot.run \
  --directory "$EAAP015_STATE/atlas-release" \
  --model-dir "$EAAP015_STATE/e5-model" --port 18743 \
  --playwright-package "$EAAP015_STATE/browser/package.json" \
  --output "$EAAP015_STATE/atlas-release-evidence"
python3 -m pilot.report \
  --browser "$EAAP015_STATE/atlas-release-evidence/browser.json" \
  --output pilot/scenarios/atlas-learning-center/reports/eaap-015 \
  --recovery "$EAAP015_STATE/recovery.json" \
  --evaluation "$EAAP015_STATE/core-evaluation/results.json" \
  --comparison "$EAAP015_STATE/core-comparison.json"
```

Final result: 39/39 named groups passed, retaining all original 32 names and
adding seven. Real React/HTTPS/Spring/PostgreSQL paths exercised named discovery,
cross-user exclusion, multiple memberships, switching, revocation/zero memberships,
actual first-use records, return-refresh, synchronized upload notices and explicit
GET-only recovery. Existing creator-private history, approval separation,
rejected/archive/closed restrictions, durable lost-response recovery and task
replay assertions still passed. Synthetic SDK output is visibly labeled.

There were 47 screenshots and zero uncaught JavaScript/unexpected console errors.
Expected negative HTTP/fault events and cancelled reads remain in the evidence;
they are not hidden as successful network requests. Nine areas were driven at
375/768/1440 px with page-overflow checks and keyboard/scroll controls. Inspected
captures and observations are linked in the
[updated usability assessment](../product/pilot-usability-assessment.md#eaap-015-focused-follow-up--2026-11-27).

All ten Atlas retrieval labels ranked their expected document first. Eleven
Atlas uploads each produced one real chunk, with observed READY latency
3.451–3.483 seconds. The 66 explicit browser-context HTTP checks had median
25.5 ms and p95 123 ms, including driver overhead and negative cases. These are
local scripted timings, not load-test throughput or human completion times.

Earlier failed attempts are excluded from these results. A real failed agent
read exposed missing explicit recovery; the button and GET-only unit/browser
regression were added. Final browser acceptance ran after Docker lifecycle
rehearsals finished. Concurrent network changes were a possible cause of the
earlier failure, not an established diagnosis.

Versioned [JSON evidence](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/acceptance.json),
[workflow summary](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/acceptance.md),
[sanitized component counts](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/component-validation.json)
and [actual image locks](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/image-locks.json)
preserve provenance without operator configuration, passwords, JWTs or raw test
environment properties. Source fingerprints describe the dirty implementation
under test; the final delivery commit is identified separately in the PR/report.

The offline suite also passed all 24 authorization/boundary cases. Its hosted
dispatch count and paid call count are both zero; synthetic contract responses
are excluded from model-intelligence claims.

## Independent encrypted recovery and operations

Executed against a fresh synthetic source and a different empty PostgreSQL
cluster, with source and destination services isolated from development volumes:

```sh
python3 -m deploy.scripts.acceptance \
  --source-config "$EAAP015_STATE/recovery-source/pilot.json" \
  --destination-directory "$EAAP015_STATE/recovery-restore" \
  --report "$EAAP015_STATE/recovery.json"
```

All 20 operational checks and all nine actual TCP-peer identity/ingress checks
passed. All 18 table row counts and canonical row hashes matched after the
independent restore, including nine Flyway history rows, identity, knowledge,
private conversation history, immutable approvals and exactly-one internal task.
Discovery before/after restore returned only the caller's organization; runtime
still lacked generic users/memberships SELECT, and the capability remained owned
by migration with narrow EXECUTE. Wrong targets, interrupted restore and invalid
secrets/JWTs failed closed. Real runtime/database outages and controlled restart
preserved the established recovery behavior.

The authenticated encrypted archive was 164607 bytes; measured archive creation
was 0.378 seconds and database restore 1.587 seconds. These are local synthetic
measurements, not a production RTO/RPO. The whole rehearsal took 487.274 seconds.
Eight legitimate login successes had zero false throttles in the dedicated
sequential/concurrent scenarios; expected overload/contention requests were
throttled. Hash concurrency peaked at two. No paid provider calls occurred.

The recovery rehearsal used the final V9/backend/runtime application source. Its web image
preceded only the later explicit GET-only agent-list refresh button; the final
browser acceptance validates that UI addition separately. This evidence is not
represented as a clean committed release: source revision, dirty-source hashes
and separately built image identities are retained in the experiment reports.

## Plans, deviations and review questions

Final local review: `git diff --check` passed; all local Markdown file targets
in changed/new documentation resolved. Source/secret/header gates passed, and a
targeted private-key/JWT/provider-key/personal-credential scan found no matches
across the 113 task files. Screenshots were guarded against visible credentials
and inspected as described above. V1–V8, runtime application sources, core-v1 and
the accepted Atlas manifest have no diff. No unrelated files or development data
were changed. Owned rehearsal containers/networks were stopped/removed; synthetic
volumes and restricted external operator evidence remain for explicit review.

No architectural deviation. A narrow V9 was explicitly authorized because tenant
names cannot be discovered with existing runtime identity privileges. PostgreSQL
rejected a custom function SET clause under the migration role; the final function
uses explicit transaction-local save/bind/restore, with no privilege elevation.
Earlier failed exploratory runs are not counted as acceptance successes.

Privacy inventory covers all storage/derived content, FK/approval/replay dependencies,
audit holds, operator approval, provider exposure and old-backup resurrection.
Deletion/offboarding, retention schedules and legal compliance are not implemented
or assumed approved. The hosted plan has 12 final cases plus one seed, maximum 13
deliberate dispatches/ concurrency 1, existing context/output/time bounds, explicit
stop conditions and human evidence review; no paid inference is authorized or run.
Human first-time-user research has a consent/observation protocol and blank outcome
criteria; no participants or human completion times are invented.

Remaining limits: only current bounded pages inform guide indicators; search
completion is not tracked; no provider-availability API exists; duplicate/stale
organization names require support identifiers/refresh; there is no source-byte
viewer. Shared-NAT/process rate-limit and existing upstream security/support
limitations remain those of EAAP-013. No full accessibility certification,
production RTO/RPO, hosted-quality or human-usability claim is made.

Work remains subject to independent product, architecture and security review.
EAAP-016 has not started. Final branch/commit/push/PR/CI evidence is reported only
after actual remote success; no merge or force push is authorized.
