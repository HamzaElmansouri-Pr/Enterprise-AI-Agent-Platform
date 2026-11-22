# EAAP-013 — Supported Spring baseline and identity ingress

Implementation evidence; pending independent architectural/security review. No production launch or merge is authorized.

## Baseline assessment and scope

Started from a clean accepted master `68d51a76e904e1c86e2f8c4ebd6512be720eccef`, confirmed PR15 merged and its [master CI run](https://github.com/HamzaElmansouri-Pr/Enterprise-AI-Agent-Platform/actions/runs/45066812893) succeeded. Branch: `feat/eaap-013-identity-spring-hardening`. Inspected AGENTS.md, ADRs0001–0011, standards, authentication/access/conversation/evaluation/security tests, runtime dependencies, Dockerfiles and pilot deployment/recovery boundaries. Original login protection was5/peer/minute,20/process/minute,2hash slots with a1024-entry bound; Nginx became the shared peer. The accepted scheme ignored spoofable forwarding headers correctly. Its accidental five-login pilot bottleneck and expired free support required the explicitly authorized EAAP-013 changes.

Accepted Java baseline `verify` passed270 tests (91unit,179integration). Unchanged Python/frontend/evaluation/deployment suites were also run. No development volume was migrated or recreated; every acceptance database belonged to a new disposable project. V1–V8 are byte-for-byte unchanged, with no V9 or entity/schema rewrite.

## Implementation and files

- `services/control-plane/pom.xml`: Boot4.2.0 modular starters and managed dependency modernization; focused Tomcat11.0.29 patch; explicit PDFBox3.0.8 and BouncyCastle1.87 retained.
- Java API/infrastructure/test imports: Jackson3, Boot health/security/environment/web-test packages and Testcontainers2 compatibility. Strict immutable JsonMapper replaces removed parser properties. Moved JPA repository exclusion preserves offline operators; probes remain private in pilot.
- `identity/api/LoginProtection.java`, `IdentityController.java`:20/minute global JSON admissions, two immediate verification permits, one per normalized keyed identity, reliable finally cleanup. No source/header channel or account cooldown. `ArgonPasswords` measures actual concurrent matches; fixed private operational counters distinguish global, hash and identity rejections.
- `access/api/AcceptanceProtection.java`: retain the existing independent invitation policy without coupling it to revised login admission.
- `deploy/pilot/nginx.conf`: bounded TCP-peer zone, exact POST login key,10/minute/burst9/nodelay, generic429/layer marker/nominal6second retry hint. Existing TLS, safe headers, private routing and disabled access logs preserved.
- `deploy/scripts/{identity_ingress,ingress_client,ingress_smoke}.py`: owned synthetic TLS/socket tests. Full pilot acceptance incorporates the actual-Spring identity experiment before unchanged recovery. CI adds the light native Nginx check within the existing job/time limits.
- [ADR0012](../architecture/decisions/0012-identity-ingress-and-supported-spring.md), [identity operations](../engineering/identity-ingress-operations.md), pilot/recovery/threat-model updates and [machine-readable reports](../../deploy/reports/eaap-013/) describe implemented boundaries and actual evidence.

## Compatibility matrix

Versions come from the [baseline](../../deploy/reports/eaap-013/baseline-runtime-dependencies.txt) and [new resolved runtime inventory](../../deploy/reports/eaap-013/runtime-dependencies.txt), not inferred from the parent version alone.

| Component | Accepted | Selected/resolved | Compatibility changes | Verification |
| --- | --- | --- | --- | --- |
| Java / Boot | 21 /3.5.16 | 21 /4.2.0 | Modular webmvc/security-resource-server/Flyway starters; environment/health packages and factories key | Compile, full verify, offline operators, pilot container startup |
| Framework |6.2.19 |7.1.0 | Servlet6.1, moved integration infrastructure | Exact HTTP routes/security/errors/JSON and JDBC regressions |
| Security |6.5.11 |7.2.0 | Existing lambda DSL/PathPattern matchers remain; no legacy auth fallback | RS256/expiry/issuer/audience, current identity/membership and scoped CSRF/default denial |
| Jackson |2.21.7 |3.1.8 (annotations remain FasterXML) | tools.jackson packages, immutable JsonMapper builder, propertyNames, unchecked exceptions; JSR310 native | Strict duplicate/trailing/unknown/type tests; persisted citations/history and runtime contracts |
| Hibernate / JPA |6.6.53.Final /3.1 |7.4.15.Final /3.2 | Boot offline repository exclusion renamed DataJpaRepositoriesAutoConfiguration; no entities or ORM rewrite | Fresh V1–V8, offline migration/no-op; DDL none |
| Flyway |11.7.2 |12.11.0 | Dedicated Boot starter activates explicitly requested operator configuration | Fresh/upgrade/repeat/validate migrations, unchanged checksums and privileges |
| Tomcat / Servlet |10.1.59 /6.0 |11.0.29 /6.1 | Boot manages11.0.27; targeted supported-line security patch to29 | Actual servlet API/auth/error/private management and container tests |
| PostgreSQL JDBC |42.7.12 |42.7.14 | Managed patch, same explicit transaction/context model | PostgreSQL17, current memberships, forced RLS, rollback/context cleanup/recovery |
| Log4j API/bridge |2.25.5 |2.26.1 | Managed security release; logging backend remains Logback | Resolved inventory, no log4j-core, secret-log checks |
| Test platform |Testcontainers1.21.4 |2.0.5 | Renamed PostgreSQL/JUnit artifacts/package, non-generic container | Disposable real PostgreSQL integration suite |

Official [GA release](https://spring.io/blog/2026/11/19/spring-boot-4-2-0-available-now/), [Java/Servlet requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Boot support metadata](https://api.spring.io/projects/spring-boot/generations/4.2.x), [Framework support metadata](https://api.spring.io/projects/spring-framework/generations/7.1.x) and retained [metadata snapshot](../../deploy/reports/eaap-013/official-support.json) were checked2026-11-22. Boot4.2/Framework7.1 OSS support ends2027-12-31. The metadata's nominal initial month-end date differs from the19November actual GA announcement; stable artifacts resolved successfully. No paid support, RC or milestone release is used.

## Advisory disposition

Review date2026-11-22. The all-runtime Maven advisory query covers109 coordinates. Zero query matches is a time-specific database observation, not a clean bill of health: the primary Tomcat bulletin revealed newer fixes absent from that query, so a narrow11.0.29 override is retained. No external-target security scan was performed. Recheck official bulletins and package metadata before exposure and on the operator patch cadence.

| Advisory | Affected baseline/range and actual target | Preconditions / reachability / disposition |
| --- | --- | --- |
| [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) | Framework6.2≤19,7.0≤8; target7.1.0 | SSE view fragments absent;7.0.9 OSS fixes; target beyond affected ranges |
| [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) | Framework6.2≤19 and older lines,7.0≤8; target7.1.0 | Implicit wildcard XsltView absent;7.0.9 OSS fixes; target beyond affected ranges |
| [CVE-2026-59273](https://spring.io/security/cve-2026-59273/) | Security6.5≤12,7.0≤8,7.1≤2 and older lines; target7.2.0 | Externally bound ELRequestMatcher absent; fixed7.1.3/7.0.9; target outside affected ranges |
| [CVE-2026-59329](https://spring.io/security/cve-2026-59329/) | Same relevant Security ranges; target7.2.0 | Reactive user-details authentication absent; existing servlet Login verifies dummy hash; fixed7.1.3/7.0.9 |
| [CVE-2026-59330](https://spring.io/security/cve-2026-59330/) | Security7.0≤8,7.1≤2; target7.2.0 | WebAuthn absent; fixed7.1.3/7.0.9; no affected configured path |
| [CVE-2026-59333](https://spring.io/security/cve-2026-59333/) | Security7.1≤2; target7.2.0 | IP/CIDR authorization matchers absent; fixed7.1.3; origin enforcement stays at Nginx |
| [GHSA-7hhh-6rmp-j9qf](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf) | Jackson2.19–2.21.6;3.0–3.1.6 and other branches; target3.1.8 | DataInput malformed-token parser; API uses bounded bytes;3.1.7 fixes; target patched |
| [GHSA-j92g-9f8w-j867](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-j92g-9f8w-j867) | JDBC42.7.4–42.7.11; target42.7.14 | TLS channel-binding downgrade; private pilot JDBC is not TLS/channel-binding;42.7.12 fixes, target retains fix |
| [CVE-2026-49844](https://logging.apache.org/security.html) | Log4j API2.13.1–2.25.4 and2.26.0; target2.26.1 | Non-finite MapMessage JSON/layout path absent; target patched; Logback backend, log4j-core absent |

[Apache Tomcat bulletin](https://tomcat.apache.org/security-11.html):11.0.29 replaces managed11.0.27. These relevant November advisory conditions are reviewed separately from package presence:

| ID | Condition absent/limited here; disposition |
| --- | --- |
| CVE-2026-95517 | EncryptInterceptor clustering absent; removed/deprecated configuration not enabled |
| CVE-2026-106012 | Dispatch role-reference authorization absent; fixed29 |
| CVE-2026-116561 | Jakarta Authentication secureResponse integration absent; fixed29 |
| CVE-2026-116600 | OWB WebSocket integration absent; fixed29 |
| CVE-2026-116830 | Backend HTTP/2 absent; fixed29 |
| CVE-2026-117517 | Prior multipart fix incomplete; fixed29 |
| CVE-2026-118028 | RewriteValve absent; fixed29 |
| CVE-2026-118233 | HTTP upgrade leak; fixed29 |
| CVE-2026-118436 | CGI servlet absent; fixed29 |
| CVE-2026-121649 | ExpiresFilter absent; fixed29 |
| CVE-2026-133325 |28–29 TLS1.3 PSK clustering affected; no cluster/PSK configured.30 unreleased; not claimed patched |

Servlet6.1 is provided by Tomcat; Jakarta annotations3.0.0/persistence3.2/validation3.1.1 remain approved APIs, not new servlet-authentication or CDI integrations. No package alert was returned for these coordinates. The target includes no configured container clustering, CGI, OWB, RewriteValve, ExpiresFilter, WebAuthn or reactive login; adding one requires review. HTTP1.1 fixed private proxying and framework authorization remain unchanged. Image digests remain pinned; no OS-image scan, host penetration test or unknown-vulnerability guarantee is claimed.

## Verification and results

Final commands, network/recovery observations and delivery identifiers are recorded below after completion. Experimental builds identify accepted parent plus dirty source and immutable local image IDs; they are not clean production-release artifacts.

The final resolved Java build passed **276 tests:96unit +180PostgreSQL integration, zero failures/errors/skips**. [Per-suite counts](../../deploy/reports/eaap-013/java-verification.json) and the [actual image library inventory](../../deploy/reports/eaap-013/pilot-images.json) retain evidence. Runtime jars in the immutable pilot image contain Framework7.1.0, Security7.2.0, Jackson3.1.8 and Tomcat11.0.29, with no Jackson2 databind fallback. The accepted270 tests remain, with5 new limiter unit tests and1 new login/header integration test. The existing peer-limit expectation was intentionally replaced by a20-global-budget assertion and the new six-successes-through-one-peer test; no failing business/security test was suppressed.

| Command (repository root unless stated) | Actual result |
| --- | --- |
| `cd services/control-plane && ./mvnw -B -ntp verify` |276 passed; real disposable PostgreSQL17/pgvector; fresh/upgrade/repeated V1–V8 and RLS |
| Maven `dependency:list -DincludeScope=runtime` and OSV query |109 runtime coordinates reviewed; official bulletin disposition above |
| Runtime `uv run --locked ruff check .`, `ruff format --check .`, `pytest` |Lint/format passed;118 tests passed |
| Web `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` |All passed;88 tests in5files; production bundle built |
| `python3 -m evaluation validate --dataset evaluation/datasets/core-v1` |Valid72-case unchanged manifest hash |
| `python3 -m unittest discover -s evaluation/tests -v` |48 passed |
| Evaluation/deploy Ruff checks and format checks |Passed; no disabled lint rules |
| `python3 -m unittest discover -s deploy/tests -v` |18 passed, including real OpenSSL authenticated negative archive tests |
| `python3 -m deploy.scripts.ingress_smoke` |Actual Nginx/TLS peer isolation, forwarding-header stripping,429/Retry-After and static paths passed |
| `python3 -m deploy.scripts.check`, `git diff --check`, changed-document link check |Passed; no credentials/archives/source maps introduced, references resolve |
| npm audit / pinned production Python pip-audit |Zero findings;61 production Python packages; time-specific metadata checks |

Local execution used the existing Java21/Maven cache and Node22/uv tool installations under `/tmp`; socket/Testcontainers commands required the sandbox's local network/Docker permission. No test was reported passed from a skipped or rejected sandbox run. The first sandboxed Python attempt was interrupted when its local transport stalled; the socket-enabled repeat completed118/118.

## Real offline evaluation on final images

Executed `python3 -m evaluation run --dataset evaluation/datasets/core-v1 --suite all --mode offline --model-dir /tmp/eaap005-e5-model --output /tmp/eaap013-reviewed-evaluation`, then `python3 -m evaluation compare --baseline evaluation/reports/baseline-core-v1/results.json --candidate /tmp/eaap013-reviewed-evaluation/results.json --output /tmp/eaap013-reviewed-comparison.json`.

Final experiment `eval-c0c557ff12b6477ea1d7abb8531f0e30`:72/72 complete,24/24 boundary checks,16 synthetic SDK contract calls,0 paid calls. Actual pinned E5 CPU inference and pgvector retrieval were used. All retrieval metric deltas against the accepted baseline are zero. Across44 labeled queries, Recall@1=.75, Recall@3=.943182, Recall@5=.977273; MRR=.889646. Forty-eight real searches had median59.74ms/p95=94.85ms. These are sequential local measurements, not an SLO. Mock provider calls prove contracts/history/citation validation, not hosted answer quality or prompt-injection resistance. Owned containers/networks/volume were removed successfully.

See [complete case results](../../deploy/reports/eaap-013/offline-evaluation.json), [summary](../../deploy/reports/eaap-013/offline-evaluation-summary.json) and [baseline comparison](../../deploy/reports/eaap-013/evaluation-comparison.json). Benchmark labels and accepted baseline artifacts were not changed.

## Final HTTPS identity and recovery rehearsal

Executed `python3 -m deploy.scripts.pilot init-local --directory /tmp/eaap013-reviewed-source --model-dir /tmp/eaap005-e5-model --port 21443`, `pilot build --config /tmp/eaap013-reviewed-source/pilot.json`, and `python3 -m deploy.scripts.acceptance --source-config /tmp/eaap013-reviewed-source/pilot.json --destination-directory /tmp/eaap013-reviewed-restore --report /tmp/eaap013-reviewed-acceptance.json`.

The final patched-image drill passed **18 operational/recovery checks and9 identity-ingress checks**. The [complete synthetic report](../../deploy/reports/eaap-013/pilot-recovery.json) preserves individual TCP source/status/layer/latency observations and table hashes. Helpers used actual bound sockets in an owned Linux network namespace with verified TLS, plus a separate ingress-only namespace for private-service denial. This proves the tested TCP-peer semantics; it does not identify real internet subscribers or model a future CDN.

| Scenario | Observed result |
| --- | --- |
| Two accounts, one peer/ingress |6/6 sequential logins200; replaces original shared five-login failure |
| Two different peers/accounts concurrently, within cap |2/2 logins200; zero below-cap legitimate false throttles |
| Same account concurrently |One200, one application429; no post-operation account lockout |
| Eight distinct identities concurrently |Two generic401 verifications admitted, six hash-cap429; actual Argon2 peak2 |
| Forwarded/XFF/X-Real-IP/X-Forwarded-Host/X-Forwarded-Proto/custom identity spoofing |All six attempts retain exhausted original peer's ingress429; other TCP source still logs in200 |
|21 sources, malformed JSON |First20 return400, final application429; no password verification required |
| Static frontend / GET login |Twelve static responses200 while peer login budget exhausted; GET login403, unaffected by POST limiter |
| Unauthorized ingress-only network |Direct Spring8080/private9090, Python8000 and PostgreSQL5432 all denied |
| Nginx/Spring restarts |Independent state reset verified; no durable identity lockout |

There were11 successful logins across all scenarios (8 deliberate below-cap successes plus restart/peer/contention observations). Across67 mixed requests:200=23 (including12 static),400=25,401=4,403=1,429=14. Rejections:6 ingress,8 application (one global, one identity, six hash overload). The zero false-throttle observation applies to the8 below-cap legitimate requests; intentional saturation did reject valid credential attempts. It is not a guarantee under attack.

Sequential legitimate median204.85ms; two-client legitimate median431.93ms. Sample groups are too small for a useful per-group p95; individual observations are retained. Across the mixed67-request workload, median19.95ms/p95=392.03ms includes cheap/static/negative responses and must not be called successful-login latency. CPU limits, local scheduling, TLS handshakes and simultaneous rehearsals affect these measurements. There is no sustained throughput, DDoS-resistance or availability-SLO claim. Global budget/hash work/memory remain bounded; distributed abuse and shared-NAT contention remain operational risks.

Encrypted export produced154359 bytes in0.278s; authenticated independent restore took1.116s. End-to-end407.98s includes deliberate outage/restart and networking checks, not a guaranteed RTO/RPO. All18 platform tables (17business plus Flyway history) retained exact row counts/hashes. V8 validation/no-op migration, forced RLS, original identities/memberships, invitations, knowledge search, creator conversation history, approvals and original task replay survived. Wrong-key/corrupt/truncated/unauthenticated archive failures are covered by the executed crypto suite; the drill additionally rejects wrong cluster/nonempty target/missing roles and rolls back an interrupted restore transaction. Missing runtime secrets/invalid signing keys fail closed, diagnostics stay private, runtime outage preserves core identity, and DB outage yields readinessDOWN/livenessUP with actual Docker unhealthy observation. Logs passed secret/private-content checks.

Three synthetic rehearsal iterations completed while framework compatibility and actual hash instrumentation were refined; the retained report is the final Tomcat11.0.29 run. Test image labels truthfully identify accepted parent68d51a7 plus a dirty experimental source build, with immutable image IDs/jar digest/source fingerprint in the inventory. They are not represented as clean deployable release artifacts. Owned rehearsal containers/networks were removed; protected synthetic volumes and external evidence remain for inspection. Accepted archives and development data were untouched.

## Delivery and remaining review

Required commit message: `feat: harden login ingress and migrate to supported Spring baseline`. Branch: `feat/eaap-013-identity-spring-hardening`; target: master. Exact delivered SHA, successful push, PR URL and final GitHub Actions result are reported in the delivery response/PR. The existing three CI jobs and timeouts remain; Java regressions cover the new admission behavior and the Python job also runs the real Nginx boundary smoke test. The PR must remain unmerged for independent architectural/security review.

No new business endpoint, dependency product, schema/V9, model/provider, paid invocation, public deployment, commercial purchase or EAAP-014 work occurred. Remaining review assumptions: direct-to-Nginx peers, single-process limits/restart resets, shared-NAT contention, global-budget denial risk, invitation's unchanged peer quota, absent Tomcat cluster/PSK functionality, host/Docker trust, internal plaintext, GUC assertion boundary, secret/off-host backup custody, retention and future CDN trust. Framework support is now an actively supported OSS line; the conditional unreleased clustering fix is documented rather than claimed patched. These limits and the synthetic experiment's scope require human review before real exposure.
