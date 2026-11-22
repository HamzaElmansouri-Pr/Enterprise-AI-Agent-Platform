# ADR 0004 — Knowledge ingestion and semantic retrieval

Status: implements the approved EAAP-005 scope; pending independent architectural review. EAAP-000 through EAAP-004 are accepted. Builds on [ADR 0001](0001-postgresql-tenant-isolation.md), [ADR 0002](0002-authentication-and-tenant-authorization.md), and [ADR 0003](0003-agent-configuration-domain.md). [Local operations](../../engineering/knowledge-operations.md) and [task evidence](../../tasks/EAAP-005.md) accompany this record.

## Ownership and storage

The control-plane `knowledge` module follows API → application → domain, with specific JDBC, extraction, embedding-client and job adapters implementing application ports. Domain types validate upload/text/vector limits and document lifecycle invariants without Spring/JDBC dependencies. Spring owns customer JWT/membership checks, roles, metadata, source bytes, extraction, chunking, associations, durable orchestration, vector SQL and citations. FastAPI owns only fixed local CPU embedding computation; it has no customer JWTs, role decisions, tenant selection, database credentials or database connection.

PostgreSQL remains the sole persistent store. V4 adds:

- `knowledge_documents`: immutable document/tenant identity, sanitized filename/type/size/checksum, fixed model revision, state, chunk count, safe failure code, timestamps, and private lease/fencing fields.
- `knowledge_content`: separate immutable BYTEA source keyed by `(tenant_id,document_id)`. No source-download API exists.
- `agent_documents`: shared many-to-many associations, keyed by tenant/document/agent, with composite foreign keys to same-tenant documents and existing agents.
- `knowledge_chunks`: immutable text, ordinal, reliable nullable page, `vector(384)` and fixed model identity; composite document FK and unique tenant/document/ordinal.

V1–V3 are unchanged. V4 adds `(tenant_id,id)` uniqueness to agents for its composite FK. All four knowledge tables have ENABLE and FORCE RLS. Tenant/document and tenant/creation indexes support actual access paths. Retrieval uses exact cosine distance; no HNSW/IVFFlat, alternative store or additional deployment is introduced.

Source BYTEA is intentionally limited to small documents: 2 MiB per upload and 20 documents per tenant, including FAILED documents. A BEFORE INSERT trigger acquires a transaction-scoped advisory lock derived from the authorized tenant and checks the quota. Competing inserts serialize; rejected inserts roll back both metadata and source. Lock hash collisions only serialize extra work. Sources are not copied per association or into chunk rows. Document deletion/retention management requires a later approved task.

## Inputs, extraction and chunking

Multipart upload accepts exactly one `file` and optional `agentId`. Servlet limits are 2 MiB/file and 2 MiB + 16 KiB/request; bounded reads also enforce the file limit. Only `text/plain` with `.txt` and `application/pdf` with `.pdf` are supported. Filenames are stripped, limited to 160 Unicode code points, and reject path separators, control/bidi characters, malformed Unicode and dot traversal names. TXT decoding is strict UTF-8, with NUL and >120,000 code points rejected before persistence. PDF uploads require `%PDF-` at the beginning; full structural parsing happens asynchronously. Structural/page/text failures become FAILED, not a false successful-ingestion response.

Apache PDFBox **3.0.8** extracts selectable PDF text, one page at a time, with a bounded writer. More than 25 pages, encrypted PDFs (including empty-password encryption), malformed PDFs, NUL, oversized extracted text, and no useful text are rejected with safe codes. No OCR, page rendering, scripts, attachments or external resources are executed/fetched. PDFBox/FontBox diagnostics are disabled in the HTTP process because malformed object diagnostics can echo private data; safe processing codes remain available. Parsing is in-process with one worker and bounded file/page/output sizes, not a hardened parser sandbox or antivirus service. Complex hostile compressed PDFs remain a resource risk requiring production reassessment.

Chunking normalizes CRLF/CR to LF while preserving Unicode and useful line breaks. It targets 800 code points, prefers a newline or sentence terminator between 500 and 800, and overlaps the previous 120 code points. It never splits a code point; chunks are nonblank, stable in order, at most 1,200 code points (current policy emits at most 800), with at most 256 chunks and 120,000 total extracted code points. Pages are chunked independently, so PDF page citations are reliable; TXT chunks have document-level citations (`sourcePage: null`). No token-chunking library or fabricated page number is used.

## Model identity and computation boundary

Approved model: [`intfloat/multilingual-e5-small`](https://huggingface.co/intfloat/multilingual-e5-small), artifact revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`. Persisted identity is `intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3` everywhere. Python **FastEmbed 0.9.1**, locked **ONNX Runtime 1.32.0**, CPU provider, two inference threads, mean pooling and L2 normalization produce 384 dimensions. FastEmbed's custom-model registration uses this exact approved official ONNX artifact rather than silently substituting a supported different model. Input prefixes are explicitly `passage: ` and `query: `; the model tokenizer applies its 512-token bound. Some multilingual 800-character chunks can exceed it, so their semantic representation may omit a tail while the complete original chunk remains stored and returned. Token-aware recall improvements require later evaluation of the approved initial character policy.

An explicit setup module downloads only fixed official files at the pinned revision, flattens the ONNX/tokenizer assets into an operator-selected directory outside the repository, and records SHA-256 digests in a revision manifest. Runtime verifies the manifest/files before loading and uses `specific_model_path`, `local_files_only`, CPU-only inference, and no startup downloads. The manifest checks local artifact consistency; it is not a signed software supply-chain attestation. Setup's trusted HTTPS/revision and protected local artifacts remain operator trust boundaries. Missing/corrupt artifacts return explicit unavailable errors with no fake fallback.

Internal contract: `POST /internal/embeddings`, `X-Embedding-Secret`, strict JSON `{kind: "passage"|"query", texts: [...]}`. Batch size is 1–8; each text is 1–1,200 characters; body is at most 64 KiB. Response is `{modelId, dimension:384, vectors}`. Unknown fields/types, missing/wrong secret, busy inference and unavailable artifacts fail explicitly. Validation errors never echo input. One nonblocking inference lock allows one operation; excess receives 429. Uvicorn also limits concurrent requests to eight/backlog sixteen. Java permits one outbound call at a time, connects within three seconds, applies a 45-second complete-response deadline/cancellation, caps response bytes at 128 KiB, rejects redirects, and validates dimensions, model, finite values and unit norm before persistence.

`compose.knowledge.yaml` injects a generated secret into only Java/Python, mounts prepared model assets read-only, and removes Python's host port. The browser talks only to Spring. `/health` remains available inside the network and indicates process health, not loaded-model readiness. There is no cookie/customer JWT forwarding, arbitrary model/URL input, GPU assumption, paid service or LangGraph execution. The static internal secret and trusted local Docker network are an MVP boundary, not production service identity, TLS or zero-trust networking.

## Authorization and API

Every customer route uses the existing signature/claim/expiry/enabled-user checks and current database membership. One membership lookup per use case produces `AuthorizedTenant`; client roles/tenant headers/JWT claims cannot authorize anything.

| Action | OWNER | ADMIN | MEMBER |
| --- | --- | --- | --- |
| Upload, retry, add/remove association | Allow | Allow | Deny |
| List/read metadata, search | Allow | Allow | Allow |

Base: `/api/v1/tenants/{tenantId}/knowledge`.

| Route | Successful response |
| --- | --- |
| POST `/documents` (multipart) | 202, durable PENDING metadata |
| GET `/documents?page=0&size=20` | 200, items/page/size/hasNext |
| GET `/documents/{documentId}` | 200, metadata/status/safe failure code |
| POST `/documents/{documentId}/retry` (no body) | 202, FAILED → PENDING |
| POST `/documents/{documentId}/agents/{agentId}` (no body) | 200, idempotent association |
| DELETE `/documents/{documentId}/agents/{agentId}` (no body) | 200, idempotent removal only |

Metadata includes id, tenantId, filename, contentType, byteSize, sha256, status, embeddingModel, chunkCount, failureCode, createdAt, updatedAt, agentIds and hasMoreAgentIds. Sources, chunks, lease tokens and vectors are not metadata fields. Associated IDs are ordered and bounded to the first 100 with an explicit more-results indicator, preventing unbounded status/list output; complete association browsing is a future contract. Pagination defaults 0/20, size 1–100, offset at most 10,000; size+1 determines hasNext without COUNT. Creation ordering uses creation time descending and UUID descending. Offset pagination can shift under concurrent inserts.

Both association resources must be tenant-accessible. New associations share-lock/check a DRAFT agent in the same short transaction; archive cannot race past that check. An existing association is idempotent even after agent archival. Removing one association leaves the source/chunks and other associations intact. Archived agents retain metadata associations but cannot search.

Search: `POST /api/v1/tenants/{tenantId}/agents/{agentId}/knowledge/search` with strict JSON `{query,topK}`. Query is nonblank, valid Unicode, at most 1,000 code points; topK defaults to 5, range 1–10. Body is capped at 16 KiB. Agent visibility/lifecycle is checked before inference and again in the final tenant transaction. No transaction remains open during model calls. The final relational query joins chunks → READY documents → that agent's current associations and filters tenant and the fixed model identity. Order is actual cosine similarity descending, then document UUID/ordinal/chunk UUID; LIMIT bounds results. The score is `1 - cosine_distance`, clamped for floating-point drift to [-1,1]; larger is closer, not a calibrated probability or answer confidence. Results contain documentId, filename, chunkId, text, score and nullable sourcePage. No generated answer or runtime execution exists.

Missing/invalid/expired token or disabled identity: 401. Missing membership/role: 403. Inaccessible document/agent: tenant-local 404. Archived agent or retry outside FAILED: 409. Invalid payload: 400; HTTP multipart oversize: 413; unsupported request media: 415; embedding unavailable/busy: 503. Unexpected errors use existing sanitized 500 conventions. Ordinary retries never duplicate concurrent work: only a conditional FAILED → PENDING transition succeeds; other states return 409 deterministically. Exact method/path security rules and scoped stateless Bearer CSRF exemptions are added; default denial and CSRF elsewhere remain.

## Durable worker and tenant capability

The uploaded source and PENDING row commit atomically before HTTP 202. A single in-process scheduled worker polls one job every three seconds; there is no in-memory backlog or new broker/worker deployment. Durable rows provide bounded per-tenant backpressure via the quota. Upload does no PDF extraction or inference. The worker reads one source in a short transaction, extracts/chunks outside transactions, computes batches outside transactions, then commits all immutable chunks plus READY/count in one transaction. Failed inserts roll back every chunk. FAILED/PENDING/PROCESSING documents are excluded from search. Safe failure codes include INVALID_PDF, ENCRYPTED_PDF, PAGE_LIMIT, TEXT_LIMIT, NO_EXTRACTABLE_TEXT, CHUNK_LIMIT, EMBEDDING_UNAVAILABLE and PROCESSING_FAILED.

Background work cannot fabricate an `AuthorizedTenant` or inherit a customer token. A narrow migration-owned SECURITY DEFINER function, with empty search_path and PUBLIC execution revoked, claims one pending/expired job using conditional UPDATE and FOR UPDATE SKIP LOCKED. It returns only document UUID, tenant UUID and random fencing token; never source bytes/text/vectors or arbitrary requested tenant discovery. The migration-role RLS policies apply only to pending/processing metadata and permit only processing claims; they do not grant runtime a role membership or access to unrelated tables. A second fixed function verifies the exact unexpired tenant/document/token and renews its two-minute lease. Runtime cannot assume the migration role or manage functions/policies.

`JdbcKnowledgeJobs` then establishes unchanged transaction-local context on that same Spring-bound connection. All worker repository operations use the ordinary runtime login and tenant RLS. Null/forged/expired capabilities and nested transactions fail closed. Renewal locks/fences the document row during each short work transaction. Each embedding batch renews first; the total call deadline stays below lease duration. A crash leaves PROCESSING durable; once its lease expires, the next poll reclaims it with a new token, fencing the old worker. PENDING work is naturally rediscovered after restart. Database outages leave durable work for a later poll; logs are rate-limited and sanitized. Graceful shutdown interrupts the worker; incomplete leased work follows the same recovery rule.

The guarantees target this approved single-process development deployment. Multiple control-plane replicas could claim different jobs; distributed global concurrency and coordinated production worker management are not implemented. An already authorized in-flight operation may finish after membership changes, as in ADR 0002. Runtime SQL credential compromise remains outside the asserted-tenant RLS boundary; trusted dispatcher access is not customer authorization.

## Privileges, rollout and resources

The operator explicitly creates administrator-owned `extensions.vector` and grants only schema USAGE to migration/runtime. Neither HTTP nor migration receives extension/database CREATE rights. V4 refuses to proceed without the expected extension namespace. Repeated provisioning and migrations are safe no-ops; fresh and V3→V4 upgrade paths are tested. See operations for exact setup and recovery commands. Flyway remains off in normal HTTP startup.

Runtime gets explicit SELECT/INSERT on knowledge tables, UPDATE only on document processing state/count/failure/timestamps/private lease fields, and DELETE only on tenant-scoped associations. It cannot update immutable identity/source/chunks, delete documents/content/chunks, truncate, own tables, modify policies, assume migration/admin roles, or bypass RLS. Missing context exposes no rows. Composite FKs prevent cross-tenant joins even when IDs are guessed. Vector type and checks reject wrong dimensions, non-finite or zero vectors; model IDs are constrained. Application validation independently requires normalized finite vectors. Sensitive SQL row/parameter logging defaults from EAAP-004 remain required.

The existing four-connection JDBC pool and statement/lock timeouts remain; short tenant/job transactions have five-second bounds. No DB connection is held while embedding. Maximum exact-search scale is 20 × 256 chunks/tenant, with bounded returned text/topK. There is no membership cache, N+1 query dispatch, artificial throughput benchmark or new telemetry product. Existing request correlation/status/timing and safe worker events are reused.

The pinned model assets occupied about 465 MiB on the measured Linux CPU host. Separate synthetic real-model checks observed about 913 MiB peak process RSS for initialization/short multilingual inference and about 909 MiB for a maximum batch of eight 1,200-character passages. The opt-in Python service cap is 1,536 MiB; the baseline non-knowledge service remains 512 MiB. These are observations from this environment, not universal memory/latency guarantees. Java remains 768 MiB; the complete four-service local setup remains practical within a 16 GiB host budget, subject to workload/OS overhead.

Back up and review DDL locking before V4: the agent composite unique index/FK validation can lock existing tables. Migration DDL is transactional; failure rolls back. Preserve administrator extension ownership. Applied migration defects require a reviewed forward version; do not edit V1–V4 or hide drift with repair. Extension setup is a separate privileged transaction and may remain installed if V4 fails. No automatic drop extension, down migration, volume reset or source deletion is provided.

Future approved tasks may consume retrieved passages as untrusted RAG context, with explicit prompt/tool/approval boundaries and evaluation. This task implements no generated answers, provider settings, vector index tuning, document UI, OCR, general scheduler, source deletion or agent execution. Real-adapter smoke tests and deterministic vector SQL tests establish different evidence; fake test vectors do not prove real semantic quality.
