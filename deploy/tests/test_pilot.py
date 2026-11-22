"""Resolve the real Compose profile and reject dangerous mutations."""

import copy
import json
import os
import subprocess
import unittest
from unittest.mock import patch

from deploy.scripts.pilot import ROOT, OperationError, local_docker, validate_plan


class PlanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        env = os.environ.copy()
        env.update(
            {
                name: "synthetic-only"
                for name in (
                    "PILOT_WEB_IMAGE",
                    "PILOT_CONTROL_PLANE_IMAGE",
                    "PILOT_RUNTIME_IMAGE",
                    "PILOT_SECRET_DIR",
                    "PILOT_MODEL_DIR",
                    "PILOT_ISSUER",
                    "PILOT_AUDIENCE",
                    "PILOT_KEY_ID",
                )
            }
        )
        env.update(
            PILOT_SECRET_DIR="/tmp/eaap012-config-test",
            PILOT_MODEL_DIR="/tmp/eaap012-config-test",
        )
        env.update(
            PILOT_RELEASE_SHA="a" * 40, PILOT_BIND="127.0.0.1", PILOT_PORT="18443"
        )
        result = subprocess.run(
            [
                "docker",
                "compose",
                "-p",
                "eaap-pilot-unit-test",
                "--env-file",
                "/dev/null",
                "-f",
                str(ROOT / "deploy/pilot/compose.yaml"),
                "config",
                "--format",
                "json",
            ],
            capture_output=True,
            env=env,
            check=True,
        )
        cls.plan = json.loads(result.stdout)
        cls.config = dict(bind="127.0.0.1", providerMode="disabled")

    def test_reviewed_profile(self):
        validate_plan(self.plan, self.config)

    def test_unsafe_container_mutations_rejected(self):
        mutations = (
            ("postgres", "ports", [{"target": 5432, "published": "5432"}]),
            ("control-plane", "read_only", False),
            ("agent-runtime", "privileged", True),
            ("control-plane", "cap_add", ["SYS_ADMIN"]),
            ("web", "user", ""),
            ("web", "cap_drop", []),
            ("web", "network_mode", "host"),
            ("agent-runtime", "environment", {"OPENAI_API_KEY": "synthetic"}),
            ("postgres", "image", "postgres:latest"),
        )
        for service, field, value in mutations:
            with self.subTest(service=service, field=field):
                plan = copy.deepcopy(self.plan)
                plan["services"][service][field] = value
                with self.assertRaises(OperationError):
                    validate_plan(plan, self.config)

    def test_public_dependency_network_rejected(self):
        plan = copy.deepcopy(self.plan)
        plan["networks"]["data"]["internal"] = False
        with self.assertRaises(OperationError):
            validate_plan(plan, self.config)

    def test_remote_docker_rejected_before_connection(self):
        with (
            patch.dict(
                os.environ, {"DOCKER_HOST": "tcp://remote:2375", "DOCKER_CONTEXT": ""}
            ),
            patch("deploy.scripts.pilot.checked") as command,
        ):
            with self.assertRaises(OperationError):
                local_docker()
            command.assert_not_called()
