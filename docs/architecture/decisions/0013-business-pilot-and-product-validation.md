# ADR 0013 — Business pilot and product validation

Status: scope authorized by EAAP-014; implementation and evidence pending independent product, architecture and security acceptance. EAAP-000–013 are accepted. This record does not authorize EAAP-015 or public deployment.

## Context and scenario

Atlas Learning Center is an entirely fictional Moroccan language/admissions organization. Its registration documents, MAD fees, schedules, support and internal follow-up work exercise the existing product coherently without creating a school ERP. Rif Training Institute supplies a separate tenant with different synthetic policies. All account addresses use the reserved `example.test` domain; no student contact details or external messages are used. [The inventory](../../../pilot/scenarios/atlas-learning-center/inventory.md) records existing functionality before changes.

## Decisions and boundaries

Reuse ADRs 0001–0012: Spring owns current membership, tenant authorization, persistence, retrieval orchestration and tool execution; Python owns bounded E5 inference and LangGraph; React presents actual HTTP outcomes. Keep V1–V8 unchanged, forced RLS, creator-only conversations, strict inputs, existing role powers, independent approval/execution and task replay guarantees. No database migration, provider, framework or product role is added.

Use an independent `atlas-v1` corpus and ten document-level retrieval labels. TXT/PDF fixtures have expected facts, byte counts and SHA-256 hashes; an explicitly obsolete conflict source tests ambiguity without silently contradicting current policy. Do not blend Atlas metrics into core-v1.

Run the accepted HTTPS pilot with fresh operator-generated secrets, signing material, tenant owners and a new PostgreSQL volume. Normal development containers and data are outside every scenario command. Only initial owner provisioning uses the approved offline identity operator; invitations, agents, uploads, conversations and approved actions use actual UI/API capabilities.

The default conversation experiment uses the existing EAAP-011 SDK MockTransport. A test-only bind-mounted entry point first imports the real app under valid disabled-provider pilot settings, then constructs its execution service using the existing synthetic transport. This entry point is absent from application images and production configuration. Pilot preflight, file secrets, internal runtime network, CPU/concurrency bounds and the production provider adapter remain unchanged. No provider egress or paid credential is supplied. Synthetic answers identify themselves explicitly. A lost browser response after a real backend dispatch is a transport-fault injection, not a fabricated successful API response; recovery uses the existing request UUID read endpoint.

Correct the invitation token HTML pattern for Chromium Unicode sets while preserving the existing 43-character URL-safe alphabet and server canonical-token checks. Accept focused UI changes backed by browser evidence: explain durable failures and source repair, show association names from one bounded accessible-agent page, retain support IDs, clear a completed upload selection, show the complete selected tenant UUID and explain that internal follow-up tasks send no messages. Missing names beyond that page have an explicit fallback; no per-association N+1 fetching or unauthorized name discovery is introduced.

## Evidence and limits

Actual Chromium renders built assets at 375/768/1440 × 1000 and operates real APIs. Browser trust is pinned to the generated local certificate's SPKI; API/operator clients verify the local CA. Credentials remain in protected external input files or memory and are excluded from reports/screenshots. Production still requires a reviewed clean release and real TLS policy; dirty local-test images are identified as experimental.

Reports distinguish real E5/pgvector retrieval, synthetic model contracts, persisted business correctness, unmeasured hosted-answer quality and unestablished human usability. Automated steps and durations are machine measurements, not customer productivity or conversion claims. Screenshots require actual visual inspection; DOM tests alone are insufficient.

Operational onboarding, tenant discovery/names, customer guidance, identity recovery, privacy operations and real hosted-quality review remain product/architecture decisions. Creating an internal task is not a CRM or a delivered communication. See [assessment](../../product/pilot-usability-assessment.md), [operations](../../engineering/business-pilot-operations.md) and [task evidence](../../tasks/EAAP-014.md).
