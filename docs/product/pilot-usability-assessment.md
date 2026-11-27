# Atlas Learning Center synthetic usability assessment

EAAP-014 evaluates the accepted console, not a replacement product. The experiment uses fictional institutions/accounts/policies and no hosted provider calls. The [pre-change inventory](../../pilot/scenarios/atlas-learning-center/inventory.md) and [versioned fact manifest](../../pilot/scenarios/atlas-learning-center/manifest.json) define scope. Engineering evidence is pending independent product, architecture and security review.

## Method and quality boundaries

Real Chromium operates built React assets over loopback HTTPS and real Spring/PostgreSQL/LangGraph. Real E5/pgvector performs retrieval. Synthetic SDK responses test contract handling only and display `SYNTHETIC CONTRACT ANSWER`. Transport-fault injection deliberately loses a real dispatched response to test read-only recovery. Negative roles, tenant IDs, revocation and terminal-state attempts use actual server enforcement. All three viewport heights are 1000 CSS pixels; full-page screenshot image heights vary with content. Counts/timings describe automation, not first-time human comprehension, business productivity or conversion. Hosted answer quality and human usability remain unmeasured.

Initial screenshot inspection covered the actual baseline dashboard, document library and failed-document detail. These demonstrated technical UUID-only associations, terse processing codes without repair guidance and an upload input retaining its previous filename after accepted upload. Final screenshot references and measured outcomes are recorded with the generated evidence; visual inspection is separate from DOM unit tests.

## Findings and focused changes

| ID | Priority | Observation / evidence | Disposition |
| --- | --- | --- | --- |
| UX-09 | P1 high | Chromium rejected the invitation token character class under its native Unicode-sets pattern syntax, producing two console errors and ignoring browser pattern validation. | Use an equivalent valid alternation pattern; malformed-token constraint tested in Chromium and Vitest. Backend canonical-token checks unchanged. |
| UX-01 | P1 high | Failed PDF detail displayed only `INVALID_PDF` alongside Retry processing. A business user could repeatedly retry identical corrupt bytes without knowing how to repair the source. | Plain-language failure/recovery notice explains new valid PDF upload, unsupported scans/encryption/limits, unavailable services, invalid citations and ambiguous provider timeout. Raw safe code retained for support; unknown backend details never reflected. |
| UX-02 | P1 high | Associated agents were only UUIDs, preventing ordinary identification of the configured assistant. | Names from one bounded accessible-agent page; IDs remain secondary. Explicit fallback for names outside that page, no N+1 lookup or new discovery API. |
| UX-03 | P2 medium | Accepted upload cleared React file state but browser input still displayed the prior file, suggesting it remained ready for upload. | Clear the real file input on successful acceptance; selection retained on rejected upload. |
| UX-04 | P2 medium | Header truncated tenant UUID to eight characters. Tenant name is unavailable in the accepted context contract. | Show full verified UUID; no fabricated name or new tenant-discovery API. Named tenant selection remains a product gap. |
| UX-05 | P2 medium | Persisted follow-up task could be misunderstood as delivered student communication. | Task heading explicitly describes internal work and says no email/message is sent. Existing separate proposal/approval/execution remains. |
| UX-06 | P2 medium | Selecting a navigation area already open at a detail does not reset that area's state. | Existing Back controls work; retained. Harness uses explicit navigation to exercise list entry consistently. |
| UX-08 | P2 medium | Actual footer foreground #78858e on #f5f6f8 measured 3.503:1 for small text. | Use the existing muted color #596775, measured 5.362:1. No new design system. |
| UX-10 | P2 medium | The immediate upload-acceptance notice can still say pending after the library has refreshed to READY. | Remaining copy refinement; the per-document status is authoritative. |
| UX-11 | P2 medium | Another user accepting an invitation does not automatically refresh the inviter’s open directory. | Use Refresh access or reopen Team; final responsive captures show all three current roles and accepted invitations. |
| UX-12 | P2 medium | Narrow tables deliberately scroll horizontally and action columns can start off-screen. | No page overflow; native focus/scroll reaches actions. Add clearer scroll guidance after human usability review. |
| UX-07 | P3 low | Dense technical quotas, IDs and operational wording remain throughout console. | Preserve traceability and scope; recommend human task testing before broader copy changes. |

The completed local acceptance established no P0 defect in the tested journeys; all three confirmed P1 findings above were fixed. Harness selector/path/startup errors are recorded as engineering corrections, not fabricated application UX defects. Passing automation must not be presented as independent acceptance.

## Product gap classification

| Category | Capability / gap | Recommendation |
| --- | --- | --- |
| 1 — Implemented and verified | Invitation-only roles, DRAFT agents, document processing, local semantic retrieval, private persistent history, separate approval/execution and internal task replay | Retain regression evidence and seek independent review. |
| 2 — Implemented but difficult | Offline owner bootstrap, manual tenant UUID selection, external secure invitation-token handoff, operator-driven failure recovery | Prioritize guided onboarding/tenant identification after an explicit architecture decision. |
| 3 — Partial | Agent guidance, bounded association-name display, source citations without a source-byte viewer, limited task lifecycle and conversation discovery | Validate priority with actual business users; do not imply missing APIs exist. |
| 4 — Missing, required for a real pilot | Actual hosted answer-quality review, human usability sessions, approved onboarding/support process, reviewed privacy/retention/deletion operations and clean release readiness | Require a bounded separately approved pilot-readiness milestone before real customer data. |
| 5 — Desirable later | Better course-specific guidance, stronger conversation navigation, customer communication integration if validated by users | Product discovery; no integration implementation in EAAP-014. |
| 6 — Out of current scope | School ERP, payments, CRM replacement, public signup/portal, WhatsApp/email sending, autonomous tools and cloud/public deployment | Excluded; internal task creation does not deliver a communication. |

Existing authentication has no account recovery, mailbox verification or general tenant lifecycle UI. Deployment remains a single-host pilot with documented backup limits, no HA/PITR guarantee, no external penetration-test claim and the accepted upstream security limitations from EAAP-013. Automation cannot decide whether those are acceptable for a real customer.

## EAAP-014 recommendation (historical)

After independent EAAP-014 review, define a milestone for guided onboarding, customer-facing failure recovery, approved privacy operations and a small explicitly budgeted hosted-answer evaluation plus observed human usability sessions. The EAAP-015 results below supersede the onboarding gaps in this historical recommendation.


## Completed evidence and visual inspection

[Machine-readable report](../../pilot/scenarios/atlas-learning-center/reports/acceptance.json) and [human-readable results](../../pilot/scenarios/atlas-learning-center/reports/acceptance.md): 32/32 completed named browser groups, zero uncaught JavaScript or unexpected console errors, 41 genuine final captures. Expected negative HTTP/fault console messages are counted separately. Ten independent Atlas retrieval labels all ranked their expected document first; the corpus is small and does not establish general business-answer quality.

Actual final Chromium screenshots were opened and inspected, including:

- [Login](../../pilot/scenarios/atlas-learning-center/reports/screenshots/login.png), [agent configuration](../../pilot/scenarios/atlas-learning-center/reports/screenshots/agent.png), [knowledge library](../../pilot/scenarios/atlas-learning-center/reports/screenshots/knowledge.png) and [failed ingestion with guidance](../../pilot/scenarios/atlas-learning-center/reports/screenshots/ingestion-failure.png).
- [Conversation and citations](../../pilot/scenarios/atlas-learning-center/reports/screenshots/conversation.png), [approved proposal before execution](../../pilot/scenarios/atlas-learning-center/reports/screenshots/approval-approved.png), [one executed OPEN task](../../pilot/scenarios/atlas-learning-center/reports/screenshots/executed-task.png) and [current team](../../pilot/scenarios/atlas-learning-center/reports/screenshots/viewport-1440-team-access.png).
- [375 px navigation/overview](../../pilot/scenarios/atlas-learning-center/reports/screenshots/viewport-375-overview.png), [375 px library](../../pilot/scenarios/atlas-learning-center/reports/screenshots/viewport-375-knowledge-library.png), [375 px closed conversation](../../pilot/scenarios/atlas-learning-center/reports/screenshots/viewport-375-conversations.png) and [768 px team](../../pilot/scenarios/atlas-learning-center/reports/screenshots/viewport-768-team-access.png). The long mobile conversation was also inspected at original resolution, not only a reduced preview.

The inspected samples show visible field labels, wrapped message/citation text, clear synthetic answers, real READY/FAILED/APPROVED/OPEN/CLOSED states and usable navigation. All nine areas were also driven at each width with page-overflow assertions. Tables retain intentional internal horizontal scrolling; this is not a claim of zero horizontal scrolling within tables. Keyboard coverage includes dialog Escape, restored trigger focus and normal browser form validation; no full screen-reader audit or WCAG certification is claimed.

Before-fix evidence: [PDF failure detail](../../pilot/scenarios/atlas-learning-center/reports/screenshots/before-ingestion-failure.png), [retained upload selection](../../pilot/scenarios/atlas-learning-center/reports/screenshots/before-knowledge.png), [actual invalid-pattern console events](../../pilot/scenarios/atlas-learning-center/reports/before-invitation-constraint.json). No screenshots contain passwords, JWTs or invitation tokens. `team.png` captures the earlier inviter view before external acceptance was refreshed; use the final responsive team captures for the current directory.

## EAAP-015 focused follow-up — 2026-11-27

[New machine evidence](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/acceptance.json)
and [workflow results](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/acceptance.md)
record 39 passing named groups: all original 32 plus seven onboarding/recovery
groups. There were zero uncaught JavaScript or unexpected console errors and
47 actual screenshots. Expected negative HTTP/fault console events and cancelled
reads are recorded separately. No paid inference or human study occurred.

| Earlier gap | Verified improvement | Remaining limit |
| --- | --- | --- |
| UX-04 UUID-only organization selection | Authenticated names/current roles, explicit choice, fresh context verification and secondary support IDs. Real second-membership acceptance/switching and revocation were exercised. | Duplicate/stale names may require the support ID and refresh. No public signup or automatic selection. |
| UX-10 stale PENDING upload notice | Accepted-upload copy points to the authoritative row; real uploads reached READY/nonzero chunks without a stale pending claim. | Processing remains asynchronous and bounded refresh can stop; manual refresh remains. |
| UX-11 external acceptance refresh | Return/focus refresh exposed independently accepted ADMIN and MEMBER records. Manual refresh retained; issued tokens/confirmation/write pause automatic refresh. | No real-time subscription or background unbounded polling. |
| UX-12 hidden table actions | Visible sideways-scroll instructions, focusable scroll region and clear focus outline; narrow view scrolled to actual management controls. | Tables still scroll internally. This is not a full accessibility certification. |
| UX-07 dense operation concepts | State-based first-use guide, named primary workspace label, clearer citations/lifecycle/permission guidance. | The seven-step guide is long on a phone; comprehension and assistance needs require human observation. Source-byte viewing remains absent. |
| Failed agent lookup | A real transport failure showed an error; an explicit Refresh agents GET recovered the selector. No automatic write/model retry. | Persistent service failures still require operator investigation. |

Opened and visually inspected the final [375 px named picker](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/screenshots/organization-selection-375.png),
[multiple organizations](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/screenshots/multiple-organizations.png),
[empty guide](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/screenshots/first-use-empty.png),
[375 px populated overview at original resolution](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/screenshots/viewport-375-overview.png),
[375 px team actions and focus](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/screenshots/viewport-375-team-access.png),
[768 px library](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/screenshots/viewport-768-knowledge-library.png),
[1440 px conversation/citations](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/screenshots/viewport-1440-conversations.png)
and [failed ingestion guidance](../../pilot/scenarios/atlas-learning-center/reports/eaap-015/screenshots/ingestion-failure.png).
Names, role/state badges, ordinary text rendering, wrapping and support details
are visible. The team capture intentionally shows the focused table scrolled to
its action columns; the skip link is visible in that keyboard-focus condition.
DOM/browser assertions also covered all nine areas at all three widths with no
page-level overflow. Captures alone do not establish human comprehension.

Earlier engineering attempts are not counted as passing acceptance: a failed
agent-list read exposed the missing explicit recovery button, which was added
and tested. The final run was isolated from concurrent Docker lifecycle drills;
the earlier network failure's root cause is not asserted as proven. Hosted answer
quality, fluent multilingual assessment and real first-time-user outcomes remain
unmeasured. See the [human protocol](human-usability-test-plan.md),
[hosted experiment plan](hosted-quality-evaluation-plan.md) and
[EAAP-015 report](../tasks/EAAP-015.md) for the independent review boundaries.
