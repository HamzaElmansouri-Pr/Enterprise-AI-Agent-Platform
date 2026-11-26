"""Safety contracts for the local launcher; no Docker or paid services required."""

import contextlib
import fcntl
import io
import json
import runpy
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import Mock, patch

LAUNCHER = runpy.run_path(str(Path(__file__).resolve().parents[2] / "runEAAP"))
credentials = LAUNCHER["credentials"]
verify = LAUNCHER["verify"]
start = LAUNCHER["start"]


class LocalLauncherTests(unittest.TestCase):
    def test_credentials_are_private_stable_and_synthetic(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "login.json"
            first = credentials(path)
            self.assertEqual(first, credentials(path))
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertTrue(first["email"].endswith("@example.test"))
            self.assertGreaterEqual(len(first["password"]), 16)

    def test_refuses_symlink_without_changing_target(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "target"
            target.write_text("untouched")
            link = Path(directory) / "login.json"
            link.symlink_to(target)
            with self.assertRaises(ValueError):
                credentials(link)
            self.assertEqual("untouched", target.read_text())

    def test_invalid_saved_credentials_are_not_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "login.json"
            data = credentials(path)
            data["password"] = "short"
            path.write_text(json.dumps(data))
            before = path.read_bytes()
            with self.assertRaises(ValueError):
                credentials(path)
            self.assertEqual(before, path.read_bytes())

    def test_login_and_current_owner_are_both_required(self):
        data = {
            "email": "test@example.test",
            "password": "synthetic-test-password",
            "tenantId": "tenant",
            "userId": "user",
        }

        def response(value):
            stream = Mock()
            stream.read.return_value = json.dumps(value)
            manager = Mock()
            manager.__enter__ = Mock(return_value=stream)
            manager.__exit__ = Mock(return_value=False)
            return manager

        for role, tenant, succeeds in [
            ("OWNER", "tenant", True),
            ("MEMBER", "tenant", False),
            ("OWNER", "other", False),
        ]:
            with self.subTest(role=role, tenant=tenant):
                opener = Mock()
                opener.open.side_effect = [
                    response({"accessToken": "synthetic-token"}),
                    response({"tenantId": tenant, "userId": "user", "role": role}),
                ]
                with patch("urllib.request.build_opener", return_value=opener):
                    if succeeds:
                        verify(data)
                    else:
                        with self.assertRaises(ValueError):
                            verify(data)
                self.assertEqual(2, opener.open.call_count)

    def test_failed_login_does_not_attempt_tenant_verification(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError(
            "http://127.0.0.1", 401, "Unauthorized", {}, None
        )
        with patch("urllib.request.build_opener", return_value=opener):
            with self.assertRaises(urllib.error.HTTPError):
                verify(
                    {
                        "email": "test@example.test",
                        "password": "synthetic-test-password",
                    }
                )
        self.assertEqual(1, opener.open.call_count)

    def test_bootstrap_secret_transport_and_verified_output(
        self,
    ):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            data = credentials(state / "login.json")
            calls = []

            def record(args, **kwargs):
                calls.append((args, dict(kwargs.get("env", {}))))

            output = io.StringIO()
            with patch.dict(start.__globals__, {"run": record, "verify": Mock()}):
                with contextlib.redirect_stdout(output):
                    start(state)
            bootstrap = [
                item for item in calls if "compose.bootstrap.yaml" in " ".join(item[0])
            ]
            self.assertEqual(1, len(bootstrap))
            self.assertEqual(data["password"], bootstrap[0][1]["BOOTSTRAP_PASSWORD"])
            for args, _ in calls:
                self.assertNotIn(data["password"], " ".join(args))
            self.assertIn(data["tenantId"], output.getvalue())
            self.assertIn(data["password"], output.getvalue())
            output = io.StringIO()
            failure = Mock(side_effect=ValueError("No OWNER membership"))
            with patch.dict(start.__globals__, {"run": record, "verify": failure}):
                with contextlib.redirect_stdout(output), self.assertRaises(ValueError):
                    start(state)
            self.assertNotIn(data["password"], output.getvalue())
            self.assertNotIn("EAAP is ready", output.getvalue())

    def test_concurrent_start_is_rejected_before_any_docker_command(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            runner = Mock()
            with (state / "launcher.lock").open("a") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with patch.dict(start.__globals__, {"run": runner}):
                    with self.assertRaises(ValueError):
                        start(state)
            runner.assert_not_called()


if __name__ == "__main__":
    unittest.main()
