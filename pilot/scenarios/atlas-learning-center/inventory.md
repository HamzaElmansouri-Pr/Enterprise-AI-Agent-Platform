# Accepted functionality before EAAP-014 changes

Baseline: master 693d8bd60d995287fba27ef2e5dbe89b7354d374; GitHub run 45072652469 succeeded. Clean worktree; no existing pilot/ directory. ADRs 0001–0012 retain authority, including the explicitly accepted Spring Boot 4.2 baseline overriding the initial AGENTS stack description.

| Journey | Existing implementation | Constraints and friction to inspect in Chromium |
| --- | --- | --- |
| Access | Memory-only login, explicit tenant UUID verification, offline initial owner bootstrap | No tenant discovery/name API or self-service onboarding; short UUID prefix in header |
| Team | OWNER/ADMIN invitation matrix, new/existing acceptance, role/revoke confirmations | Token manually shared outside platform; no delivery; passwords 16–128 characters |
| Agents | Paginated DRAFT creation/edit, terminal archive, role-based controls | DRAFT is usable, no separate publish state |
| Knowledge | PDF/TXT multipart upload, durable worker, real E5/pgvector, bounded polling, associations, search | Association detail displays UUID alone; failure codes lack plain-language recovery |
| Conversations | Creator-only durable history, bounded context, citation validation, rename/close, request UUID replay/recovery | No paid calls authorized; test-only SDK transport must be visibly synthetic; list selected per agent |
| Proposals | Immutable manual/generated proposal, distinct manager approval, separate execution, decision evidence | Task creation is internal; must not imply external communication |
| Tasks | Real paginated persisted OPEN follow-up records, idempotent source proposal | No CRM/contact sending/completion workflow |
| Operations | HTTPS pilot, ingress quotas, private services/diagnostics, strict secrets, encrypted recovery | Disposable environment only; no development volumes touched |

Existing browser scripts are temporary EAAP-008/009/010 Playwright programs under /tmp, not a portable checked-in harness. EAAP-014 will add reproducible scenario tooling using that installed browser engine, without adding an application dependency. Existing evaluation/mock_runtime.py is test-only; production runtime has no synthetic-provider mode. No replacement frontend, migrations or new application framework are planned.
