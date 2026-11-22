# Identity ingress operations

Use the [pilot runbook](pilot-deployment-operations.md) and [ADR 0012](../architecture/decisions/0012-identity-ingress-and-supported-spring.md). This is a single-host, directly connected Nginx boundary. A CDN, load balancer or additional proxy requires a new allowlisted upstream trust design before use. Do not enable real-IP rewriting or framework forwarded-header trust.

## Limits and client behavior

| Layer | Configuration | Rejection and reset |
| --- | --- | --- |
| Nginx actual TCP peer | `deploy/pilot/nginx.conf`: 1 MiB zone, 10/minute, burst9/nodelay; exact normalized POST login only | Generic JSON429, X-Login-Limit=ingress, Retry-After6; one nominal slot each6seconds without competing traffic; full process restart clears state |
| Spring global | LoginProtection:20 admitted JSON login requests/UTC minute/process | Generic JSON429, X-Login-Limit=application, Retry-After60; next minute or process restart resets budget |
| Spring password verification | Two active operations, at most one per normalized email | Same generic429; no queue; identity digest removed in finally on completion/failure; no account cooldown |
| Invitation acceptance | Existing independent5/peer/minute,20/process/minute,2slots | Unchanged generic429; shared ingress-peer limitation still applies to invitations |

Login successes and failures both consume ingress and global budgets. Malformed JSON consumes these budgets without hashing. Unsupported content type and query-bearing requests cannot invoke password verification. Unknown, disabled and wrong-password accounts receive the same invalid_credentials401 after accepted verification. Limiter responses do not establish account existence. No account failure count is exposed.

A shared NAT group intentionally shares the ingress bucket. Different TCP peers have independent ingress buckets but share the explicit backend global workload cap. More than two simultaneous valid logins may receive429; retry after the response hint with client-side jitter and a bounded manual retry policy. The console does not automatically replay credentials. Do not interpret a429 as an incorrect password or repeatedly click login. Never raise limits or restart services automatically on429: restart clears abuse state and invalidates operational counters.

The per-identity guard stores at most two full process-keyed HMAC digests while verification runs, never plaintext email/password keys. The existing Email normalization is reused. Success and failure release the same permit; no cache, permanent lockout or success-triggered budget reset exists. A malicious concurrent attempt may temporarily win a slot. Global budget exhaustion can still deny legitimate users; these conservative bounds need workload-based review before broader exposure.

## Counters and incident triage

Use `python3 -m deploy.scripts.pilot diagnostics --config /secure/pilot/pilot.json`. Diagnostics require the private operations secret and port9090; the public ingress returns404 for these paths. Fixed additional counters are LOGIN_GLOBAL_LIMITED, LOGIN_HASH_BUSY, LOGIN_IDENTITY_BUSY and LOGIN_HASH_PEAK (high-water active verification count, at most2). Existing RATE_LIMITED counts application429 responses. No email, digest, IP, token or arbitrary metric label is added. Counters reset on Spring restart; private diagnostics do not include Nginx rejection counts.

Ingress429 carries the static layer marker and nominal6second hint; application429 carries the application marker and conservative60second hint. Count these statuses from an authorized synthetic client or an existing reviewed monitoring channel. Nginx access logs stay disabled, and limiter URI diagnostics are suppressed. There is no new telemetry backend or public metrics endpoint. Client observations and process-local snapshots are limited evidence, not an audit log or distributed count.

When throttling rises, inspect readiness, fixed counters, incident time window and reviewed synthetic status/latency distributions. Separate peer abuse/NAT contention, identity contention, hash concurrency and global admissions. Restrict abusive ingress using the operator's approved host policy if necessary. Avoid collecting credentials, headers or raw account lists. Preserve safe request IDs and aggregate counts. Treat sustained global exhaustion as an availability incident; do not disable password verification or authorization. Refer to the human incident owner and existing [threat model](../security/operational-threat-model.md).

Tuning is a reviewed code/config change: document expected active users/NAT concentration, measured success and busy/429 rates, CPU/memory and Argon2 peak concurrency. Re-run positive/negative tests and recovery. Keep global/hash bounds, memory bounds and error privacy. Do not infer an SLO from this synthetic pilot. The20/minute budget is deliberately retained; future capacity changes require measured justification.

## Safe synthetic tests

From repository root, with local Docker/Compose and OpenSSL3:

```sh
python3 -m deploy.scripts.ingress_smoke
```

This CI check uses the pinned Nginx/Python bases, temporary local TLS, a test-only stdlib HTTP upstream and reproducible source-bound loopback TCP connections. It asserts independent peer buckets, stripped spoofed forwarding headers, safe429/Retry-After and unthrottled static content. It does not claim to test real authentication.

For actual Spring identity, isolation and recovery, prepare the existing pinned E5 assets and **new nonexistent directories**, choose unused loopback ports, then run:

```sh
python3 -m deploy.scripts.pilot init-local --directory /tmp/eaap013-rehearsal-source --model-dir /absolute/path/e5-small --port 19443
python3 -m deploy.scripts.pilot build --config /tmp/eaap013-rehearsal-source/pilot.json
python3 -m deploy.scripts.acceptance --source-config /tmp/eaap013-rehearsal-source/pilot.json --destination-directory /tmp/eaap013-rehearsal-restored --report /tmp/eaap013-rehearsal.json
```

The drill owns unused random projects, refuses existing containers/volumes, disables paid providers and never selects development data. Helper containers share only Nginx's network namespace and bind reproducible127.0.0.2/127.0.0.3 sources; HTTPS verifies its local test CA. A separate ingress-only helper attempts private service connections. These are actual distinct TCP sockets on an owned Linux namespace, not claims about real internet subscribers or mocked X-Forwarded-For. Docker's random source address is not the isolation assertion. The report distinguishes six below-cap legitimate logins from intentional global/concurrency overload, statuses/layers, source identities, peak active verification and measured median/p95.

The drill also restarts Nginx and Spring, proves admission reset and exercises original encrypted independent recovery and service failure tests. Restarting web clears peer buckets; restarting Spring clears only backend budget/active keys/counters, not Nginx state. This is a documented bypass opportunity for an operator who controls restarts, not durable abuse storage. Failures must remain test failures; never rerun this command against an existing customer project or copy real credentials into its fixtures.
