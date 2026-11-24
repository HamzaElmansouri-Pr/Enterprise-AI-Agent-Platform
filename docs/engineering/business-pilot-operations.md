# Synthetic business pilot operations

EAAP-014 uses only fresh, owned, loopback HTTPS pilot projects. Never point its tools at development/production data. Read [ADR 0013](../architecture/decisions/0013-business-pilot-and-product-validation.md) and the [pre-change inventory](../../pilot/scenarios/atlas-learning-center/inventory.md). No paid provider calls or public deployment are authorized.

## Prerequisites

Use the repository's pinned Java 21, Node 22, Python 3.12, Docker Compose, OpenSSL and accepted E5 model directory. Prepare local embeddings using [knowledge operations](knowledge-operations.md). The approved pilot requires image/model verification, file secrets and independent PostgreSQL; see [pilot operations](pilot-deployment-operations.md). Docker must be local Unix-socket Docker.

Reuse an existing Playwright 1.65.1 installation or prepare an external test-only directory (no application dependency):

```bash
mkdir -p /tmp/atlas-browser-tools
npm install --prefix /tmp/atlas-browser-tools --save-exact @playwright/test@1.65.1
/tmp/atlas-browser-tools/node_modules/.bin/playwright install chromium
python3 -m pilot.dataset
python3 -m unittest discover -s pilot/tests -v
```

## Complete browser experiment

Run from the repository root. Choose a **new, nonexistent** external directory and an unused loopback port. The command builds current images, creates an immutable image lock, bootstraps only the two initial synthetic owners, drives the real browser and always removes its own containers/networks. It retains its generated operator directory and synthetic volumes for explicit investigation; it removes browser-input credentials. Do not reuse a previously populated scenario database.

```bash
python3 -m pilot.run \
  --directory /tmp/atlas-experiment-001 \
  --model-dir /absolute/path/to/verified/e5-small \
  --port 18443 \
  --playwright-package /tmp/atlas-browser-tools/package.json \
  --output /tmp/atlas-evidence-001
```

No `.env` from development is loaded. Secrets/signing/TLS files stay outside the repository. Local-test dirty source is permitted and reported; it is not an immutable clean production release. Chromium pins trust to this fixture certificate's public key; the script re-executes Node with this exact public CA in NODE_EXTRA_CA_CERTS for its transport-fault request client. It does not disable TLS errors globally. The script rejects non-loopback/non-local-test targets and unexpected browser origins. Do not capture traces, HARs or screenshots of filled password/invitation forms.

For diagnosis, run the same underlying stages manually:

```bash
python3 -m pilot.environment provision --directory /tmp/atlas-manual-001 \
  --model-dir /absolute/path/to/verified/e5-small --port 18443
EAAP_PLAYWRIGHT_PACKAGE=/tmp/atlas-browser-tools/package.json \
  node pilot/browser.mjs /tmp/atlas-manual-001/browser-input.json /tmp/atlas-manual-evidence
python3 -m pilot.environment stop --config /tmp/atlas-manual-001/pilot.json
```

The protected input file contains generated passwords; never print, copy into documentation or commit it. Manual provisioning retains that input for diagnosis: remove that exact file after teardown. Provisioning does not overwrite an existing directory. `--image-lock` can reuse a reviewed local-test image lock with matching source revision; the report records actual image IDs, so do not claim reused images contain subsequent UI edits.

## Business walkthrough and roles

The generated Atlas OWNER signs in and verifies the assigned tenant UUID. OWNER invites `atlas-manager@example.test` as ADMIN and `atlas-employee@example.test` as MEMBER. Separate browser contexts accept each invitation through the new-account form, then log in independently; no staff are inserted with SQL. Roles are verified from current server context. The Rif OWNER uses its own tenant and distinct policy.

OWNER creates **Atlas Admissions Assistant** using the actual DRAFT schema. DRAFT is already usable; there is no publishing step. Upload the versioned TXT/PDF fixtures, select this agent, watch PENDING to READY and check nonzero chunks. A malformed synthetic PDF proves durable FAILED with repair guidance. Search EN/FR/Arabic/Darija queries using real local embeddings/pgvector, remove an association and prove exclusion, then restore it. The obsolete conflict file is deliberately labeled; never treat it as the current tuition policy.

Conversations are creator-private. The test-only SDK transport is attached solely to the owned runtime, after normal pilot validation, and returns `SYNTHETIC CONTRACT ANSWER`. The actual graph validates citations and bounded history. The browser tests navigation, re-login, replay, an actual persisted dispatch followed by lost response and read-only recovery, invalid citations, rename, close and unavailable-provider failure. No automated ambiguous POST retry is introduced.

OWNER manually proposes an internal missing-documents follow-up, reviews exact arguments and PENDING_APPROVAL. Neither proposal nor approval creates a task. Self/MEMBER approval is denied. The independent ADMIN approves and explicitly executes. One real OPEN task persists with matching payload; execution replay returns the same task ID. Decision evidence remains accessible. No external message is sent.

The harness also checks role/tenant denial, current-JWT revocation, temporary database outage/recovery, rejection, terminal archive, invalid login and actual login throttling. Expected negative HTTP responses generate browser network-console errors; uncaught JavaScript errors are recorded separately. Request durations and interaction counts are synthetic machine observations only.

## Evaluation, recovery and reports

Core-v1 remains separate:

```bash
python3 -m evaluation validate --dataset evaluation/datasets/core-v1
python3 -m unittest discover -s evaluation/tests -v
python3 -m evaluation run --dataset evaluation/datasets/core-v1 --suite all \
  --mode offline --model-dir /absolute/path/to/verified/e5-small --output /tmp/atlas-core-evaluation
python3 -m evaluation compare --baseline evaluation/reports/baseline-core-v1/results.json \
  --candidate /tmp/atlas-core-evaluation/results.json --output /tmp/atlas-core-comparison.json
```

Run the accepted [encrypted recovery rehearsal](backup-and-disaster-recovery.md) on independent new pilot source/restore directories with the same reviewed image lock. `python3 -m deploy.scripts.acceptance --help` gives the exact `--source-config`, `--destination-directory` and `--report` arguments. Its source project must have no existing volumes or containers; do not point it at the populated Atlas browser project. This is a separate operational regression, not a claim that the Atlas browser database itself was restored.

After a successful browser run, assemble portable evidence:

```bash
python3 -m pilot.report --browser /tmp/atlas-evidence-001/browser.json \
  --output /tmp/atlas-reviewed-report \
  --evaluation /tmp/atlas-core-evaluation/results.json \
  --comparison /tmp/atlas-core-comparison.json \
  --recovery /tmp/atlas-recovery.json
```

Inspect actual screenshots at all reported widths, review JSON/Markdown for secrets and confirm source/image provenance before committing sanitized results. Report generation itself does not claim visual or human usability acceptance. Heavy Compose/Chromium acceptance is an explicit verification workflow; CI still runs all accepted suites plus Atlas fixture validation and unit checks.

## Troubleshooting and cleanup

A failed script is not a passed acceptance run. Inspect its sanitized `browser.json` and exact failed group. A failed container command intentionally omits private response/log bodies. Check only the owned project, do not print environment/secret files or run global Docker prune. A fixture parsing failure requires a repaired upload; retrying damaged bytes will not repair them. An ambiguous conversation requires saved-outcome retrieval, not a new POST. A 429 requires respecting the actual limiter, not raising/resetting its security limits to pass the business test.

The contract adapter is a read-only test bind mount, never an image layer or production mode. Its service transition force-recreates that runtime so Docker cannot retain an earlier mounted file inode. To return it to the real disabled-provider entry point, use `python3 -m pilot.environment disabled --config /absolute/owned/pilot.json`; never enable hosted mode in this task.

Normal teardown is `pilot.environment stop` for that exact project. Volumes/keys are preserved for review; deleting them later is an explicit operator action scoped to their project labels and external directory. Development volumes are never selected by this tooling.

## Future explicitly authorized hosted pilot

Require a separate approved experiment: reviewed clean images, permitted data/retention, fixed existing model, file-delivered provider secret only in the runtime, reviewed hosted egress overlay, named bounded cases, concurrency/token/deadline limits, hard request/spending caps and independent answer/citation review. Use the existing hosted evaluation safeguards and abort on ambiguity or cap exhaustion. Do not infer production answer quality from the synthetic responses here. No such hosted experiment was executed for EAAP-014.
