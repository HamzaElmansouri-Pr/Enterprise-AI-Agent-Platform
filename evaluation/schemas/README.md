# Core dataset schema version 1

`dataset.load` is the executable strict schema validator; it has no dependency outside Python's standard library. Unknown fields, duplicate JSON keys, nonfinite numbers, unpaired Unicode surrogates, missing fields, unknown versions/labels/checks, duplicate case/document IDs, bad references, wrong counts and changed fixture hashes are rejected. UTF-8 fixtures are synthetic, not instruction sources for the evaluator.

`cases.json` has exactly `schemaVersion: 1`, `documents` and `cases`.

Each document has exactly `id` (local reference), `file` (hashed file below fixtures/) and `scope`: `associated`, `unassociated` or `other-tenant`. Actual UUIDs are resolved only after API provisioning. Expected documents may reference associated fixtures only.

Each case has exactly:

| Field | Type / purpose |
| --- | --- |
| caseId | Unique nonblank string, at most 80 characters |
| category | retrieval, supported, unanswerable, conflict, injection, citation, memory, authorization |
| language | en, fr, ar, ary (Darija; script is identified by case wording/tags) |
| question | Nonblank, valid Unicode, no NUL, at most 1000 characters |
| expectedDocumentIds | Distinct local document references; binary document relevance labels |
| expectedEvidence | Distinct editorial evidence cues; never an automated answer oracle |
| expectedBehavior | grounded_answer, abstain, clarify, resist_injection, reject, history_contract |
| tags | Distinct editorial scenario tags |
| check | Empty for retrieval/generation; named executable invariant for boundary cases |

`manifest.json` has exactly schemaVersion, datasetVersion (`core-v1`), fixtureVersion (`1`), languages, counts (by category/language), labeling, weaknesses, files (relative file → SHA-256). Hashes cover cases.json and every referenced fixture. The manifest itself is hashed in run provenance; modifying labels changes that hash. Hashes establish consistency, not independent label correctness or signature authenticity.

Reports use schemaVersion 1 and an individual row for every selected case, including not-run rows on setup failure/interruption. Status and evidence kind are independent: real retrieval, trusted boundary, mocked provider contract, hosted inference, or none. Null denominators/scores mean unavailable, never perfect or zero quality. Reviewed artifacts retain the experiment/dataset identity and record a reviewer handle and rationale. Keep reviewer handles synthetic/pseudonymous; never enter credentials or private customer text.
