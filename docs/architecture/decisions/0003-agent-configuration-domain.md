# ADR 0003 — Tenant-owned agent configuration

Status: implements the approved EAAP-004 specification; pending independent architectural review. Builds on [ADR 0001](0001-postgresql-tenant-isolation.md) and [ADR 0002](0002-authentication-and-tenant-authorization.md). No dependency or infrastructure service is added.

## Model and boundaries

The Spring Boot `agent` module owns configuration management. Domain objects have no framework, HTTP, JDBC, or persistence dependency. `AgentConfiguration` owns immutable UUID identity/tenant ownership, server timestamps, and lifecycle. `AgentDetails` owns content validation. Application use cases coordinate existing membership authorization, permissions, transactions, and the specific `AgentStore` port. Infrastructure uses the existing runtime datasource and prepared JDBC; API records explicitly map requests and responses.

Fields are `id`, `tenantId`, `name`, optional `description`, `systemInstructions`, `status`, `createdAt`, and `updatedAt`. Creation produces DRAFT. The only transition is DRAFT → ARCHIVED. Archival is terminal; repeat archive returns the existing representation without a write. Archived updates return 409. There is no physical deletion.

Name is trimmed at Unicode White_Space edges, then must contain 1–120 Unicode code points. ISO control characters, line/paragraph separators, and bidi override/isolate controls are rejected inside stored names. Description permits at most 1,000 code points; instructions require nonblank content and at most 10,000 code points. Malformed surrogate pairs and NUL are rejected. Multiline instructions are preserved literally: no environment interpolation, code execution, URL access, or authorization interpretation. Oversize content is rejected, never truncated. DTO/domain string representations redact content; logging complete records is prohibited.

## Persistence and privileges

[V3](../../../services/control-plane/src/main/resources/db/migration/V3__agent_configurations.sql) creates migration-owned `platform.agent_configurations`. V1 and V2 are unchanged. UUID primary key and tenant foreign key, VARCHAR bounds, Unicode whitespace/name-safety checks, instruction length/nonblank checks, status checks, and timestamp ordering enforce storage invariants. PostgreSQL rejects NUL/invalid encoding. The only listing index is `(tenant_id, created_at DESC, id DESC)`, alongside the primary key.

RLS is enabled and forced. SELECT requires transaction-local tenant equality. INSERT additionally requires DRAFT. UPDATE requires tenant equality and an existing DRAFT row, with the new tenant checked again. Missing context exposes no rows and permits no insert. Runtime receives SELECT/INSERT and UPDATE only on `name`, `description`, `system_instructions`, `status`, and `updated_at`. It cannot alter identity/tenant/creation time, delete, truncate, own the table, change policies, assume migration roles, or bypass RLS. Application checks remain mandatory; RLS context is not proof of membership if runtime credentials are compromised (ADR 0001).

Runtime database role logging presets suppress statement/parameter and failed-row detail logging, protecting instructions in database errors. Existing volumes require reviewed role provisioning before V3; see [operations](../../engineering/database-operations.md). This changes logging defaults, not role privileges. Operators must retain these protections when configuring diagnostics.

## Authorization and transactions

JWT validation and current-enabled-user checking remain EAAP-003 responsibilities. The existing membership query is reused once per use case through `MembershipAuthorization`; it returns an `AuthorizedTenant` capability whose constructor is confined to the identity application package. No body/query/header/JWT role is authoritative.

| Operation | OWNER | ADMIN | MEMBER |
| --- | --- | --- | --- |
| Create, update, archive | Allow | Allow | Deny |
| List, retrieve | Allow | Allow | Allow |

Authorization and write permission checks occur before tenant SQL/context establishment. `TenantTransactions` has actual reuse in both tenant-context reads and agent use cases. Its JDBC adapter starts a Spring-managed transaction, applies unchanged `PostgresTenantContext.apply` to the transaction-bound connection, executes bounded repository work, and commits/rolls back. The capability is required; null capabilities, nested transactions, and agent repository access outside the bound transaction fail closed. No ThreadLocal tenant holder, session-wide context, separate context connection, or arbitrary client SQL is introduced. Commit and rollback clear PostgreSQL's local setting, including pooled reuse.

Each update/archive reads and conditionally writes inside one transaction. SQL includes tenant and `status = 'DRAFT'`. A concurrent archive causes an update to return 409, never overwrite archived content. Archive changes only status/timestamp, preserving a concurrently completed content update. Concurrent repeat archives return the same stored result. Database timestamps advance by at least a microsecond, even if a request's clock value is older. Concurrent draft updates use last successful writer semantics; no version history or ETag contract is introduced.

## HTTP contract

Base: `/api/v1/tenants/{tenantId}/agents`, with Bearer authentication and current membership on every request.

| Method and suffix | Behavior |
| --- | --- |
| POST collection | JSON create; 201, full representation and Location |
| GET collection | Page envelope: items, page, size, hasNext |
| GET `/{agentId}` | Full representation; 200 or tenant-local 404 |
| PUT `/{agentId}` | Replace editable fields; 200, or 409 when archived |
| POST `/{agentId}/archive` | No request body; 200 with archived representation |

Create/PUT accept only `name`, optional nullable `description`, and `systemInstructions`. Example:

```json
{"name":"Admissions Assistant","description":"Answers admissions questions.","systemInstructions":"Answer clearly. Do not invent institution policies."}
```

Responses contain all eight model fields, with camelCase names and ISO timestamps. Strict JSON rejects unknown/server-controlled properties, wrong types, duplicates and trailing values. Body reading is bounded to 160 KiB (including JSON encoding), with no truncation. Invalid UUIDs, content, filters or pagination produce 400; unsupported request media types produce 415. Missing/invalid token or disabled user produces 401; missing membership/insufficient role produces 403. Inaccessible and nonexistent agent IDs both produce 404 `agent_not_found`; archived update produces 409 `agent_archived`. Other errors use existing sanitized conventions (unexpected 500 `internal_error`), without SQL or content details.

Spring Security registers exact method/path shapes only. CSRF exemptions cover the three approved stateless Bearer write shapes, plus the existing login exemption. CSRF remains enabled elsewhere; default denial, JWT validation, no cookie authentication, and existing CORS policy remain intact.

## Resource bounds and limitations

Listing defaults to page 0/size 20, accepts size 1–100 and optional DRAFT/ARCHIVED status, and rejects negative pages or `page * size > 10000` using overflow-safe arithmetic. Ordering is `created_at DESC, id DESC`. One prepared size+1 query computes hasNext; no COUNT, N+1, cache, or unbounded read. Requests reuse one membership lookup, plus existing enabled-user checking; tenant SQL uses the same bounded pool. Existing four-connection Hikari limit and PostgreSQL statement/lock/connection timeouts remain. Tenant transactions additionally have a five-second transaction timeout and agent statements a three-second query timeout.

Offset pagination may shift under concurrent inserts and becomes more expensive at larger offsets; the 10,000 offset cap bounds this initial contract. List responses include instructions and therefore can approach several MiB at maximum size; reads are restricted to authorized tenant members. There is no artificial throughput/latency benchmark or new telemetry product.

LangGraph execution, model/provider configuration, tools, retrieval, approvals, UI, versioning and scheduling require later approved contracts. This configuration module supplies no execution endpoint or runtime integration. Future consumers must treat instructions as untrusted data and enforce their own approved access/execution boundaries.

## Verification and rollout

[EAAP-004 evidence](../../tasks/EAAP-004.md) records domain, real HTTP/JWT, runtime-role RLS, pooled connection/rollback, deterministic concurrency, migration-upgrade and regression coverage. Tests use disposable databases and generated keys, never development data.

Apply V3 through the existing one-shot migration profile, with runtime services using only runtime credentials. Review/back up first: table/FK/index/policy/grant DDL uses PostgreSQL transaction semantics, may acquire locks on referenced metadata, and is not run during HTTP startup. Validate Flyway before rollout; repeated migration is a no-op. Failed DDL rolls back; applied defects require a reviewed forward migration or backup recovery, not changes to V1/V2/V3 or destructive automatic rollback. No automatic migration of an existing developer volume is performed by this task.
