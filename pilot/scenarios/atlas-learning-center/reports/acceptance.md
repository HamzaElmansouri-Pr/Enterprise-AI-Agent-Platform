# Atlas synthetic business acceptance

Source: `693d8bd60d995287fba27ef2e5dbe89b7354d374`; dirty local-test source: `True`.

Browser named workflow groups: 32 passed. Corpus: 12 documents; 10 Atlas retrieval queries.

Real Atlas retrieval Recall@1/3/5: 1.000/1.000/1.000; MRR@5: 1.000.

## Machine interaction counts

Counts include scripted click/fill/select/file interactions, not operator bootstrap commands or human thinking time. Counts include negative controls and repeat navigation/login; they are not minimum-click or human completion-time estimates.

| Journey | Interactions |
| --- | ---: |
| onboarding | 10 |
| invite-admin | 17 |
| invite-member | 18 |
| agent-setup | 8 |
| upload-and-associate | 57 |
| retrieval | 55 |
| conversation | 51 |
| proposal | 7 |
| approval | 12 |
| outage | 2 |
| responsive | 46 |

## Workflow evidence

- PASS: second tenant has distinct persisted policy and real ingestion
- PASS: browser invite, new acceptance and independent ADMIN login
- PASS: browser invite, new acceptance and independent MEMBER login
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
- PASS: browser durable ingestion failure and explicit recovery control
- PASS: real multilingual E5 and pgvector retrieval
- PASS: browser association removal excludes persisted document from real retrieval
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
- PASS: revoked membership denies existing JWT and displays accurate UI error
- PASS: invalid login and real ingress throttle have accurate visible errors
- PASS: no uncaught browser errors, external traffic, browser credential storage or secret-bearing URLs/logs

## Limits

- Real multilingual E5 embeddings and PostgreSQL pgvector
- Existing synthetic test-only SDK transport through real LangGraph; not hosted intelligence
- Actual HTTPS UI writes and persisted HTTP readbacks
- Not measured; no paid calls authorized
- Not established by scripted task completion
- Machine observed only, not human completion time or conversion evidence
