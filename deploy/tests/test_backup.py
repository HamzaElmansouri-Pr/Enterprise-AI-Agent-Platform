"""Real OpenSSL negative tests; no database or paid provider required."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from deploy.scripts import backup
from deploy.scripts.pilot import OperationError, private_file


class BackupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.workspace.name)
        for name in ("recipient", "wrong"):
            subprocess.run(
                [
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "rsa:2048",
                    "-nodes",
                    "-days",
                    "1",
                    "-subj",
                    "/CN=synthetic-backup",
                    "-keyout",
                    str(cls.directory / (name + ".key")),
                    "-out",
                    str(cls.directory / (name + ".crt")),
                ],
                check=True,
                capture_output=True,
            )
            for extension in ("key", "crt"):
                (cls.directory / (name + "." + extension)).chmod(0o600)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=self.directory)
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.cert = self.directory / "recipient.crt"
        self.key = self.directory / "recipient.key"
        self.dump = self.path / "database.dump"
        self.dump.write_bytes(b"PGDMP\x00synthetic-test-only" * 200)
        self.manifest = dict(
            format=backup.FORMAT,
            schemaVersion="8",
            postgresMajor=17,
            vectorVersion="0.8.7",
            dumpSha256=backup.sha(self.dump),
            backupId="synthetic",
        )
        self.package = self.path / "payload.tar"
        backup.pack(self.dump, self.manifest, self.package)
        self.archive = self.path / "backup.cms"
        backup.encrypt(self.package, self.archive, self.cert)

    def test_authenticated_round_trip_and_permissions(self):
        data = backup.validate(self.archive, self.cert, self.key)
        self.assertEqual(data["authentication"], "verified")
        self.assertEqual(self.archive.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(b"synthetic-test-only", self.archive.read_bytes())

    def test_wrong_key_corruption_and_truncation_rejected(self):
        for case in ("wrong-key", "corrupt", "truncated"):
            with self.subTest(case=case):
                source = self.path / (case + ".cms")
                raw = bytearray(self.archive.read_bytes())
                if case == "corrupt":
                    raw[len(raw) // 2] ^= 1
                elif case == "truncated":
                    raw = raw[:-30]
                source.write_bytes(raw)
                output = self.path / (case + ".plaintext")
                key = self.directory / "wrong.key" if case == "wrong-key" else self.key
                with self.assertRaises(OperationError):
                    backup.decrypt(source, output, self.cert, key)
                self.assertFalse(output.exists())

    def test_cbc_not_accepted_as_authenticated_backup(self):
        archive = self.path / "cbc.cms"
        subprocess.run(
            [
                "openssl",
                "cms",
                "-encrypt",
                "-binary",
                "-aes-256-cbc",
                "-outform",
                "DER",
                "-in",
                str(self.package),
                "-out",
                str(archive),
                str(self.cert),
            ],
            check=True,
            capture_output=True,
        )
        with self.assertRaises(OperationError):
            backup.validate(archive, self.cert, self.key)

    def test_no_overwrite(self):
        original = self.archive.read_bytes()
        with self.assertRaises(FileExistsError):
            backup.encrypt(self.package, self.archive, self.cert)
        self.assertEqual(original, self.archive.read_bytes())

    def test_schema_extension_version_and_digest_rejected(self):
        for name, value in (
            ("schemaVersion", "9"),
            ("postgresMajor", 18),
            ("vectorVersion", "0.9"),
            ("dumpSha256", "bad"),
        ):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as destination:
                manifest = {**self.manifest, name: value}
                package = Path(destination) / "package.tar"
                backup.pack(self.dump, manifest, package)
                with self.assertRaises(OperationError):
                    backup.unpack(package, destination)

    def test_regular_protected_secret_required(self):
        secret = self.path / "key"
        secret.write_text("synthetic")
        secret.chmod(0o644)
        with self.assertRaises(OperationError):
            private_file(secret)
        secret.chmod(0o600)
        link = self.path / "link"
        link.symlink_to(secret)
        with self.assertRaises(OperationError):
            private_file(link)

    def test_disk_plaintext_workspace_rejected(self):
        with self.assertRaises(OperationError):
            backup.secure_tmp(self.path)

    def test_authentication_precedes_destination_contact(self):
        class Destination:
            def preflight(self):
                pass

            def database_preflight(self, **kwargs):
                raise AssertionError("Unauthenticated input reached destination")

        with self.assertRaises(OperationError):
            backup.restore(
                Destination(), self.archive, self.cert, self.directory / "wrong.key"
            )

    def test_export_failure_publishes_nothing_and_cleans_tmpfs(self):
        class Source:
            command = ["false"]
            env = os.environ.copy()
            project = "synthetic"
            config = {"release": "a" * 40}

            def preflight(self):
                pass

            def database_preflight(self):
                return dict(
                    postgresMajor=17,
                    vectorVersion="0.8.7",
                    migration="8",
                    clusterId="synthetic",
                )

        output = self.path / "exports"
        with self.assertRaises(OperationError):
            backup.create(Source(), self.cert, output)
        self.assertEqual(list(output.iterdir()), [])

    def test_encryption_failure_removes_partial_output(self):
        output = self.path / "failed.cms"
        with patch("deploy.scripts.backup.subprocess.run") as run:
            run.return_value.returncode = 1
            with self.assertRaises(OperationError):
                backup.encrypt(self.package, output, self.cert)
        self.assertFalse(output.exists())

    def test_missing_encryption_credential(self):
        with self.assertRaises(FileNotFoundError):
            backup.encrypt(
                self.package, self.path / "missing.cms", self.path / "missing.crt"
            )
        self.assertFalse((self.path / "missing.cms").exists())

    def test_interruption_removes_only_new_output(self):
        output = self.path / "interrupted.cms"
        with patch(
            "deploy.scripts.backup.subprocess.run", side_effect=KeyboardInterrupt
        ):
            with self.assertRaises(KeyboardInterrupt):
                backup.encrypt(self.package, output, self.cert)
        self.assertFalse(output.exists())
        output.write_bytes(b"existing-valid-backup")
        with self.assertRaises(FileExistsError):
            backup.encrypt(self.package, output, self.cert)
        self.assertEqual(output.read_bytes(), b"existing-valid-backup")

    def test_unwritable_destination_rejected(self):
        class Source:
            def preflight(self):
                pass

            def database_preflight(self):
                return {}

        output = self.path / "read-only"
        output.mkdir(mode=0o500)
        self.addCleanup(output.chmod, 0o700)
        with self.assertRaises(OperationError):
            backup.create(Source(), self.cert, output)
        self.assertEqual(list(output.iterdir()), [])

    def test_insufficient_database_privilege_does_not_publish_or_echo(self):
        class Source:
            command = ["synthetic-database-client"]
            env = {}

            def preflight(self):
                pass

            def database_preflight(self):
                return {}

        with patch("deploy.scripts.backup.subprocess.run") as run:
            run.return_value.returncode = 1
            run.return_value.stderr = b"permission denied; private-row-marker"
            with self.assertRaises(OperationError) as error:
                backup.create(Source(), self.cert, self.path / "denied-export")
        self.assertNotIn("private-row-marker", str(error.exception))
        self.assertEqual(list((self.path / "denied-export").iterdir()), [])
