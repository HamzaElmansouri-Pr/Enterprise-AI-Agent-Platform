# Atlas synthetic business acceptance

Source: `cd7b8569168f4035e990a578acc4da8f08d8bbe3`; dirty local-test source: `True`.

Browser named workflow groups: 39 passed. Corpus: 12 documents; 10 Atlas retrieval queries.

Real Atlas retrieval Recall@1/3/5: 1.000/1.000/1.000; MRR@5: 1.000.

## Machine interaction counts

Counts include scripted click/fill/select/file interactions, not operator bootstrap commands or human thinking time. Counts include negative controls and repeat navigation/login; they are not minimum-click or human completion-time estimates.

| Journey | Interactions |
| --- | ---: |
| onboarding | 8 |
| invite-admin | 16 |
| invite-member | 17 |
| agent-setup | 8 |
| upload-and-associate | 60 |
| retrieval | 55 |
| conversation | 51 |
| proposal | 7 |
| approval | 12 |
| outage | 2 |
| responsive | 55 |

## Workflow evidence

- PASS: single-tenant named discovery excludes other organizations and first-use guide shows real empty state
- PASS: second tenant has distinct persisted policy and real ingestion
- PASS: browser invite, new acceptance and independent ADMIN login
- PASS: directory refresh after external ADMIN invitation acceptance
- PASS: browser invite, new acceptance and independent MEMBER login
- PASS: directory refresh after external MEMBER invitation acceptance
- PASS: browser agent configuration persists; role permissions enforced
- PASS: ADMIN configuration write persists but OWNER-only membership promotion is denied
- PASS: real ingestion registration.txt
- PASS: real ingestion english-fees.pdf
- PASS: real ingestion french-fees.txt
- PASS: real ingestion placement.txt
- PASS: real ingestion schedule.txt
- PASS: real ingestion refund.txt
- PASS: real ingestion support.txt
- PASS: real ingestion followup.txt
- PASS: real ingestion discount.txt
- PASS: real ingestion scholarship.txt
- PASS: real ingestion conflict-labelled.txt
- PASS: first-use records reflect actual agent/associated READY knowledge without hosted availability claims; upload notice stays synchronized
- PASS: browser durable ingestion failure and explicit recovery control
- PASS: real multilingual E5 and pgvector retrieval
- PASS: browser association removal excludes persisted document from real retrieval
- PASS: failed agent-list read recovers through deliberate GET refresh without a paid or business retry
- PASS: persistent browser conversations, citations, navigation, re-login and request replay
- PASS: actual dispatch with lost response retains request identity; read-only recovery without resubmission
- PASS: invalid citation truthful failure, rename, keyboard dialog focus and closed read-only state
- PASS: browser provider unavailable is durable failure, no fake answer
- PASS: distinct manager approval, no pre-execution effect, exactly one persisted OPEN task and replay
- PASS: cross-tenant agents/documents/conversations/runs/approvals/execution/tasks/membership denied; same-tenant creator privacy; role restrictions; no citation byte endpoint
- PASS: browser terminal rejection and denied execution
- PASS: temporary database outage gives UI error and restart preserves the real task
- PASS: all navigation areas render at 375/768/1440 without page overflow
- PASS: browser archived agent preserves history and prevents new conversations
- PASS: multiple-tenant existing-account acceptance and named switching clear prior workspace data
- PASS: revoked membership denies existing JWT and displays accurate UI error
- PASS: revoked memberships disappear immediately on discovery refresh; no-membership account is not auto-opened
- PASS: invalid login and real ingress throttle have accurate visible errors
- PASS: no uncaught browser errors, external traffic, browser credential storage or secret-bearing URLs/logs

## Limits

- Real multilingual E5 embeddings and PostgreSQL pgvector
- Existing synthetic test-only SDK transport through real LangGraph; not hosted intelligence
- Actual HTTPS UI writes and persisted HTTP readbacks
- Not measured; no paid calls authorized
- Not established by scripted task completion
- Machine observed only, not human completion time or conversion evidence
