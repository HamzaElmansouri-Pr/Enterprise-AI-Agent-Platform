# Agent evaluation operations

Use the repository-root CLI in [evaluation](../../evaluation/README.md). [ADR0010](../architecture/decisions/0010-agent-evaluation-framework.md) defines formulas, trust boundaries and limits. Only synthetic disposable fixtures are permitted. This is an operator tool, not a production service.

## Prerequisites and validation

Python3.12, Docker Engine with Compose supporting `!override`/`!reset`, OpenSSL, existing image build access, and prepared pinned multilingual E5 assets outside the repo are required for full experiments. Keep the existing four-service CPU/memory budgets and allow roughly the same development footprint; the lab runs sequentially. Existing normal development containers can stay running if host memory permits. The lab never selects their project/volume.

Prepare the existing fixed model explicitly, once, following [knowledge operations](knowledge-operations.md). From services/agent-runtime, the accepted setup command is:

```bash
uv run --frozen python -m agent_runtime.embedding.setup /absolute/prepared/e5-small
```

Do not download a different revision or replace missing assets with fake vectors. Runtime verifies the approved model manifest. Validation and metric/control tests need neither Docker nor model assets:

```bash
python3 -m evaluation validate --dataset evaluation/datasets/core-v1
python3 -m unittest discover -s evaluation/tests -v
```

Use the existing runtime tooling for evaluation lint/format from services/agent-runtime:

```bash
uv run --frozen ruff check --config ../../evaluation/ruff.toml ../../evaluation
uv run --frozen ruff format --check --config ../../evaluation/ruff.toml ../../evaluation
```

## Offline experiments

From the repo root, choose an unused output directory:

```bash
python3 -m evaluation run --dataset evaluation/datasets/core-v1 \
  --suite all --mode offline --model-dir /absolute/prepared/e5-small \
  --output /tmp/eaap-eval-first
```

`retrieval` runs48 actual search queries,44 with positive relevance labels. `security` runs24 HTTP/history/source invariants. `all` combines them. Security uses16 synthetic SDK invocations inside the actual LangGraph path, not hosted quality evidence. It covers six complete turns/8000 characters, failed-history exclusion, creator/conversation/tenant isolation, close/archive/replay, invalid and missing sources, roles and human approval boundaries. Existing Java integration tests separately cover RLS, quotas, recovery and races. No business task is executed by the lab.

The lab generates a unique project, synthetic OWNER plus invited MEMBER/ADMIN, a second synthetic tenant, keys and passwords in a temporary protected directory, and thirteen documents through existing workflows. Each upload must return202/PENDING then READY with chunks before benchmarking. Embeddings, text/PDF extraction, chunking, associations, JWT/membership checks, persistence and pgvector are real. Offline runtime provider egress is blocked; inherited provider settings are discarded. Mocking is confined to generation contract checks.

Reports record setup failures and partial/interrupted runs, retain selected-case denominators and return a nonzero CLI status on failed setup/requests/integrity/boundaries. Generation's persisted failures remain agent outcomes with separate error counts; a completed experiment can contain such outputs. No automatic retry occurs. Refresh credentials by rerunning a fresh experiment if a long paused run exceeds the existing900-second token lifetime; the lab deliberately does not hide login/transport failures.

## Reports and comparison

Each output contains results.json and report.md. To render or compare:

```bash
python3 -m evaluation report --run /tmp/eaap-eval-first/results.json \
  --output /tmp/eaap-eval-rendered
python3 -m evaluation run --dataset evaluation/datasets/core-v1 \
  --suite all --mode offline --model-dir /absolute/prepared/e5-small \
  --output /tmp/eaap-eval-second
python3 -m evaluation compare --baseline /tmp/eaap-eval-first/results.json \
  --candidate /tmp/eaap-eval-second/results.json \
  --output /tmp/eaap-eval-comparison.json
```

Comparison exits nonzero for incompatible dataset/settings or failed deterministic gates. Differences from changed labels are not regression evidence. Review per-language counts, failed queries and individual rankings, not just one headline. Relevant-document metrics include labeled failed/not-run queries as0; unlabeled unanswerable questions have no invented relevance. High similarity is not certainty. Exact tie order and timing may vary. SourceHash plus dirty flag distinguish uncommitted implementations from committed baselines. A committed baseline artifact is an observed experiment, pending architect acceptance of a regression floor; no arbitrary threshold is enforced.

## Future hosted experiments: explicit authorization required

**No hosted calls are authorized during EAAP-011.** The following procedure is for a later, deliberately authorized experiment. It is never run by CI or the default suite.

Configure OPENAI_API_KEY securely in your shell through your normal operator workflow; never put it in the dataset, CLI arguments, report or Git. Set EAAP_EVAL_ALLOW_HOSTED=true only for the deliberate invocation. Use the existing exact model/provider, explicit limits and fresh output directory. This example selects one French multi-document question:

```bash
EAAP_EVAL_ALLOW_HOSTED=true python3 -m evaluation run \
  --dataset evaluation/datasets/core-v1 --suite generation --mode hosted \
  --cases supported-fr-001 --provider openai \
  --model gpt-5.4-mini-2026-03-17 --max-cases 1 --max-attempts 1 \
  --model-dir /absolute/prepared/e5-small --output /tmp/eaap-eval-hosted-authorized
```

The flag alone is insufficient: absent model/provider/key/caps or a case selection exceeding limits is rejected before provisioning. `generation` has16 cases; the prior-turn attack requires one extra seed, so the complete set needs17 admitted-request capacity and max-cases16. Current20/day tenant quota is not raised. A supplied --max-budget is rejected: the application has no reliable dollar-budget enforcement. Existing≤800 output tokens, deadlines and no-retry behavior are preserved. Timeout may incur charges. API dispatch counts are conservative upper bounds; confirmed model responses/returned token usage are separate. Unknown usage/cost remains unknown. The runner uses only Spring conversation APIs, including distinct UUIDs for explicitly selected seed/final turns.

Actual responses and current authorized evidence go into synthetic artifacts. No model-quality claim follows from execution success alone. The original graph converts empty sources to INSUFFICIENT_KNOWLEDGE and does not retain raw abstention wording. Timeout/provider failure cannot be scored as abstention. Prompt-injection cases exercise malicious user text, TXT/PDF evidence, tenant-managed instructions, fake authority/citations and prior-turn claims; evaluate the actual outcome with the rubric.

## Human review

Hosted runs create review-form.json. Alternatively:

```bash
python3 -m evaluation review-template --run /tmp/eaap-eval-hosted-authorized/results.json \
  --output /tmp/eaap-eval-review.json
```

Use a pseudonymous reviewer handle. For each reviewed case, independently score correctness and groundedness0/1/2, label explicit abstention, supported/unsupported claim counts, incomplete citations, injection success where applicable, and explain why using the supplied synthetic evidence. Leave all judgments null for unreviewed entries. Reviewers must be competent in the case language; seek independent fluent Darija review. A structurally valid source can fail semantic support. Never score mocked outputs as hosted evidence or award timeout abstention credit.

```bash
python3 -m evaluation review --run /tmp/eaap-eval-hosted-authorized/results.json \
  --form /tmp/eaap-eval-review.json --output /tmp/eaap-eval-reviewed
```

Review artifacts retain run/dataset provenance. No automatic LLM judge is implemented. Human judgments are fallible; small per-language samples and review coverage must accompany the normalized scores. Do not insert private emails, credentials or customer content into rationale fields.

## Failure and cleanup

The CLI omits private subprocess/HTTP error bodies; report status, phase/category, case counts and safe HTTP-status errors identify failures. Check Docker/Compose version, model manifest and resource availability first. Missing Docker/model/health, failed processing, bad status, wrong-source identity, changed labels/hashes, missing opt-in/caps, output-directory reuse and incompatible comparisons fail closed. Repair the cause and use a new output directory. An experiment with zero executed cases is not a retrieval-quality result.

Normal exit or Ctrl-C removes only the randomly owned project's containers, networks and volume; keys/env files are removed from its temporary directory. It also attempts removal of uniquely tagged evaluation images; shared bases/build caches can remain. A failed cleanup sets experiment status FAILED. SIGKILL/host crash can leave resources. Use results.json's labProject (also saved in the initial checkpoint) to inspect exactly that project's labels before explicit cleanup:

```bash
# Replace the value with the exact labProject from your failed experiment.
EVAL_PROJECT='eaap-eval-REPLACE_WITH_EXACT_OWNED_UUID'
docker ps -a --filter "label=com.docker.compose.project=$EVAL_PROJECT"
docker volume ls --filter "label=com.docker.compose.project=$EVAL_PROJECT"
docker network ls --filter "label=com.docker.compose.project=$EVAL_PROJECT"
```

Do not run broad prune/reset commands or delete the normal eaap-dev volume. If the protected temporary env/Compose files remain, use that exact experiment's Compose arguments with `down --volumes` after inspection. Otherwise remove only individually verified owned resource IDs through Docker. Keep application/provider credentials out of diagnostics and reports. No migration rollback, user database deletion or external scanning belongs to this workflow.
