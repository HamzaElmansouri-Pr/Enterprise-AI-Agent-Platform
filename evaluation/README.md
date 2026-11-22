# Synthetic agent evaluation laboratory

Developer-operated Python CLI; Python 3.12 and the existing Docker Compose stack. No new application service, database, production endpoint or package dependency. See [operations](../docs/engineering/agent-evaluation-operations.md), [ADR 0010](../docs/architecture/decisions/0010-agent-evaluation-framework.md), [schema](schemas/README.md) and [dataset manifest](datasets/core-v1/manifest.json).

From the repository root:

```bash
python3 -m evaluation validate --dataset evaluation/datasets/core-v1
python3 -m unittest discover -s evaluation/tests -v
python3 -m evaluation run --dataset evaluation/datasets/core-v1 \
  --suite all --mode offline --model-dir /absolute/prepared/e5-small \
  --output /tmp/eaap-evaluation-first
python3 -m evaluation report --run /tmp/eaap-evaluation-first/results.json \
  --output /tmp/eaap-evaluation-rendered
python3 -m evaluation compare --baseline /tmp/eaap-evaluation-first/results.json \
  --candidate /tmp/eaap-evaluation-second/results.json \
  --output /tmp/eaap-evaluation-comparison.json
```

Each run builds uniquely tagged images, uses random credentials and keys, provisions a fresh randomly named Compose project/volume and removes its owned containers/networks/volume afterward. There is no external-target option. Never supply a production model key to offline tests: offline mode discards inherited keys and isolates runtime egress anyway. Prepare model assets explicitly using the existing setup workflow. Dataset validation/unit tests need neither Docker nor model files.

Suites: `retrieval` runs 48 searches (44 with relevant-document labels); `security` executes 24 application boundary/history/citation invariants, including 16 synthetic SDK invocations through the real graph; `all` combines both. `generation --mode offline` measures retrieval only; it does not measure answer quality. Six adversarial prompts are present for deliberate hosted review, with TXT/PDF evidence. Their presence and offline contract checks cannot prove model injection resistance.

Hosted mode is disabled by default, requires explicit CLI bounds, the exact existing model/provider and `EAAP_EVAL_ALLOW_HOSTED=true`. It uses only Spring conversation execution APIs; there is no second provider client. No paid hosted calls are authorized or needed for this milestone. See operations before explicitly authorizing a future experiment.

Synthetic reports are safe to inspect, but runtime credentials/keys remain temporary and are never report fields. Do not commit arbitrary external reports. Committed examples below reports/ contain only this project's owned synthetic fixtures. Direct RLS checks remain in the existing real PostgreSQL Java integration suite; the CLI does not acquire runtime SQL authority.
