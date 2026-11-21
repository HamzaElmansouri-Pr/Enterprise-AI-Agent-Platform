# Security standards

Security is release-blocking under [AGENTS.md](../../AGENTS.md). These inherited controls apply within the [approved boundaries](../architecture/README.md). No security product or authentication implementation is selected here.

## Trust boundaries and authorization

- Consider authentication, authorization, tenant isolation, least privilege, confidentiality, validation, output handling, secure errors, audit logging, and resource exhaustion for every change.
- Never accept a client tenant identifier as proof of access. Enforce tenant authorization in trusted application code and prevent cross-tenant reads and writes.
- Never use LLM output or prompts as authorization decisions. Tool permissions belong in trusted code in the Spring Boot Tool Gateway; the agent runtime cannot bypass it.
- Require approval for state-changing agent actions in the initial release. Apply idempotency to retryable business actions.
- Use application-owned authorization even with database defense in depth. Verify ordinary application roles cannot bypass database row-level security.

## Inputs, tools, and data

- Validate inputs at trust boundaries, use parameterized database access, and handle outputs safely.
- Treat uploads, retrieved knowledge, external outputs, and model-generated content as untrusted. Knowledge is data, not instructions; generated answers are not verified facts.
- Protect URL-fetching functionality against SSRF. Do not expose unrestricted shell, network, or database tools to agents.
- Bound model context, tokens, execution steps, tool calls, concurrency, retries, and external timeouts. Use explicit tool schemas, approval checkpoints, traceable model calls, and controlled recovery.

## Secrets and telemetry

Never commit real secrets or credentials. Do not log tokens, API keys, sensitive prompt content indiscriminately, complete private documents, or conversations by default. Keep secrets and sensitive business data out of telemetry and secure error responses.

Use consistent correlation and trace IDs across services. Capture appropriate request lifecycle, service errors, graph execution, tool outcomes, approvals, external latency, token use, estimated cost, and relevant database metrics without leaking confidential data.

## Verification and decisions

Security-sensitive implementation requires explicit positive and negative tests, including authorization and cross-tenant isolation where relevant. Test denied actions, approval enforcement, and failure paths as applicable; use the [testing strategy](testing-strategy.md). Do not weaken controls or tests to pass a build.

Before affected implementation, obtain architectural decisions on authentication, tenant identity propagation, approval semantics, database roles/isolation, and any unspecified security-sensitive contracts. No additional frameworks, secret stores, identity providers, or scanning products are approved by this document.
