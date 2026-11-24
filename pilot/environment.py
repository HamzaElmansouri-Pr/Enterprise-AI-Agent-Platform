"""Guarded setup for a fresh HTTPS business experiment, never a target URL."""

import argparse
import json
import secrets
import shutil
import uuid
from pathlib import Path

from deploy.scripts.acceptance import start_database
from deploy.scripts.failures import wait_health
from deploy.scripts.pilot import ROOT, Pilot, build, init_local, private_file


def provision(directory, model_dir, port, image_lock=None):
    config = init_local(directory, model_dir, port)
    print("Building locked images for the fresh synthetic pilot", flush=True)
    if image_lock:
        shutil.copyfile(image_lock, Path(directory) / "images.json")
    else:
        build(config)
    pilot = Pilot(config)
    start_database(pilot)
    pilot.database_preflight(empty=True)
    pilot.operator("migration")
    tenants = {name: str(uuid.uuid4()) for name in ("atlas", "rif")}
    for name, title in (
        ("atlas", "Atlas Learning Center"),
        ("rif", "Rif Training Institute"),
    ):
        pilot.operator(
            "identity-bootstrap",
            {
                "BOOTSTRAP_TENANT_ID": tenants[name],
                "BOOTSTRAP_USER_ID": str(uuid.uuid4()),
                "BOOTSTRAP_EMAIL": name + "-owner@example.test",
                "BOOTSTRAP_TENANT_NAME": title,
            },
        )
    pilot.compose(["up", "-d", "--wait"])
    pilot.database_preflight()
    password = (
        private_file(Path(pilot.config["secretDir"]) / "BOOTSTRAP_PASSWORD")
        .decode()
        .strip()
    )
    # Protected machine input, outside the repository; never an evidence artifact.
    credentials = {
        "config": str(config),
        "url": f"https://127.0.0.1:{port}",
        "tenants": tenants,
        "ownerPassword": password,
        "staffPassword": secrets.token_urlsafe(24),
        "scenario": str(ROOT / "pilot/scenarios/atlas-learning-center"),
    }
    path = Path(directory) / "browser-input.json"
    path.touch(mode=0o600)
    path.write_text(json.dumps(credentials))
    print("Synthetic pilot ready; protected browser input: " + str(path), flush=True)
    return path


def contract(pilot, enabled):
    if not pilot.config["localTest"] or pilot.config["providerMode"] != "disabled":
        raise ValueError("Only a locally owned disabled-provider test is allowed")
    overlay = pilot.path.parent / "contract.json"
    if enabled:
        overlay.write_text(
            json.dumps(
                {
                    "services": {
                        "agent-runtime": {
                            "volumes": [
                                str(ROOT / "evaluation") + ":/evaluation:ro",
                                str(ROOT / "pilot/runtime_contract.py")
                                + ":/test/runtime_contract.py:ro",
                            ],
                            "command": ["python", "/test/runtime_contract.py"],
                        }
                    }
                }
            )
        )
        pilot.command += ["-f", str(overlay)]
    pilot.preflight()
    pilot.compose(["up", "-d", "--wait", "--force-recreate", "agent-runtime"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    setup = sub.add_parser("provision")
    setup.add_argument("--directory", required=True)
    setup.add_argument("--model-dir", required=True)
    setup.add_argument("--image-lock")
    setup.add_argument("--port", type=int, default=18443)
    for operation in (
        "contract",
        "disabled",
        "stop",
        "database-stop",
        "database-start",
        "proof",
    ):
        sub.add_parser(operation).add_argument("--config", required=True)
    args = parser.parse_args()
    if args.operation == "provision":
        provision(args.directory, args.model_dir, args.port, args.image_lock)
        return
    pilot = Pilot(args.config)
    if not pilot.config["localTest"]:
        raise ValueError("This harness is limited to synthetic local-test environments")
    if args.operation in {"contract", "disabled"}:
        contract(pilot, args.operation == "contract")
    elif args.operation == "proof":
        proof = json.loads(
            pilot.compose(
                ["exec", "-T", "agent-runtime", "cat", "/tmp/evaluation-proof.json"]
            )
        )
        print(
            json.dumps(
                {
                    "calls": len(proof),
                    "maxHistory": max(len(p["history"]) for p in proof),
                    "maxHistoryChars": max(
                        sum(len(t["question"]) + len(t["answer"]) for t in p["history"])
                        for p in proof
                    ),
                    "historyLengths": [len(p["history"]) for p in proof],
                }
            )
        )
    elif args.operation == "stop":
        pilot.compose(["down", "--remove-orphans"])
    elif args.operation == "database-stop":
        pilot.compose(["stop", "postgres"])
    else:
        pilot.compose(["up", "-d", "--wait", "postgres"])
        wait_health(pilot, "readiness", (200, "UP"))


if __name__ == "__main__":
    main()
