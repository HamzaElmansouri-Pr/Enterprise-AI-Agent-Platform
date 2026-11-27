# First-time operator usability protocol

Status: prepared, **not executed**. No participants, completion rates, times or
feedback have been collected. Chromium acceptance is engineering evidence only.
The product owner must approve recruitment and thresholds before this study.

## Participants and setting

Proposed small formative sample: six first-time operators (two OWNER scenarios,
two ADMIN and two MEMBER), representing organization administration/admissions
work rather than software engineering. Include French, English and fluent
Arabic/Darija users; record preferred language and device without demographic
identifiers. Recruit people who have not read the implementation/runbooks.
This sample explores confusion, not statistically representative performance.
OWNER/ADMIN actors must be different people for the approval task. No participant
should receive elevated permissions to make a task easier.

Use a new loopback-only synthetic Atlas/Rif pilot with the exact reviewed release,
fixture manifest, test accounts, role matrix and visible synthetic-data banner in
the briefing. Operator prepares and records service/model mode beforehand, without
showing participants backend instructions. Supported browser/device: desktop 1440,
tablet 768, phone 375 CSS pixels; counterbalance device/task order across sessions.
Allow 45 minutes plus a five-minute debrief; do not tell participants a speed target.

Obtain explicit consent for observation and optional recording; declining recording
must not prevent participation. Use pseudonymous participant handles, not real
emails/customer details. Explain withdrawal, what is stored, who can review it and
the operator-approved storage/deletion date before consent. No default retention
period is enacted by this protocol. Never capture passwords, invitation tokens,
JWTs, operator configuration or private clipboard history. Screenshots only after
secret fields/tokens are cleared. Observation files stay in approved restricted
storage; no automatic analytics, session replay or new application tracking.

## Moderator script and tasks

Give each task in business language only. Ask the participant to think aloud.
Observe silently first. After 90 seconds without progress offer the standardized
prompt "What are you trying to find?"; mark assistance. If asked directly, give
one neutral navigation hint and record its exact wording. Security controls must
never be bypassed. Stop a task on participant abandonment, security/data exposure,
or a five-minute task limit; mark incomplete and the actual reason.

| Task | Prompt | Observable successful outcome |
| --- | --- | --- |
| Login / organization | "Sign in and open Atlas, not Rif. Tell me your role." | Correct named organization and current role; no guessed tenant UUID needed. Multi-membership participant chooses explicitly. |
| Agent configuration | OWNER/ADMIN: "Set up an assistant for admissions, explaining what it should answer." | One saved DRAFT agent with purpose/instructions; participant understands configuration is not a running autonomous service. MEMBER identifies manager requirement. |
| Document and readiness | "Give the assistant this registration document and tell me when it can use it." | Actual upload/association; waits for READY/nonzero chunks; distinguishes accepted upload from usable knowledge. |
| Registration information | "Which documents are needed to register? Show how you know." | Authorized search returns the registration passage; participant finds filename/source and does not interpret score as certainty. |
| Conversation and recovery | "Start a private conversation, leave it, and find it again. What would you do after a lost response?" | Saved creator-owned conversation resumed; identifies read-only outcome retrieval. Provider-disabled condition is explicitly recorded: no successful generated answer is claimed. |
| Invitation | OWNER/ADMIN: "Invite this synthetic colleague with only the needed role. Check after they join." | Real issued/accepted invitation, secure token handling, current member visible after return/refresh; understands no email was sent. MEMBER identifies restriction. |
| Approval / task | "Prepare admissions follow-up, have a colleague review it, and create the internal task." | Creator cannot self-approve; distinct manager approval creates no task; explicit execution creates exactly one OPEN task. Participant says no customer message was sent. |

Model mode is disabled by default. Starting a conversation is free; sending may
require a hosted provider. Do not enable a paid key during this study without a
separate authorization. If a disclosed synthetic contract adapter is used to
exercise the workflow, label every generated response synthetic and report that
condition separately; it establishes no model-quality evidence. Do not invent
"successful answer" credit for MODEL_UNAVAILABLE. A naturally encountered failure
is useful evidence, not permission to edit the database.

## Observation record (blank template)

For each participant/task capture: participant handle, release/fixture IDs,
assigned/current role, organization, device, provider mode, task start/end UTC,
elapsed seconds, complete/partial/incomplete/not-applicable, assistance count and
exact prompts, wrong turns, visible errors and safe support IDs, confusion quote
(redacted), abandonment/reason, participant's interpretation and feedback.
Record whether setup/availability blocked the task separately from user confusion.
Use actual human stopwatch timestamps only; subtract interruptions only with an
explicit recorded rule. Do not copy scripted Chromium durations into this form.

After each task ask: "What happened?", "What was unclear?", and "What would you do
next?". In the debrief ask whether they would feel confident operating it alone,
what they expected from citations/approval, and which wording they would change.

## Analysis and review gates

A task is independently complete only when its observable outcome is verified and
the participant required no moderator navigation hint. Report assisted completion
separately. For each task show eligible/attempted/completed/assisted/abandoned/
blocked counts; show observed time distributions only for comparable conditions.
Include every failure and excluded/not-applicable reason; do not drop slow users.

Security misunderstanding is release-blocking for review: selecting the wrong
organization without noticing, treating a proposal as executed, believing an
internal task was delivered externally, automatic ambiguous resend, or exposing
tokens. Do not reduce security controls in response. Product acceptance requires
independent review of raw redacted notes, repeated confusion, severity and proposed
fixes; there is no invented "passed usability" percentage. A larger confirmatory
study and its numerical targets require a separate approved plan.

Research owner moderates/reports; security reviewer checks access/token handling;
fluent language reviewer checks translations/interpretation; product owner decides
priorities. Store a blank study record as "not executed" until actual consented
sessions occur. This milestone supplies this protocol, not human research results.
