"""Negative restore probes against the drill's empty independent cluster only."""

import uuid
from pathlib import Path

from deploy.scripts import backup
from deploy.scripts.pilot import OperationError


def rejects(action):
    try:
        action()
    except OperationError:
        return
    raise AssertionError("Unsafe recovery action succeeded")


def empty_database_guard(pilot):
    if not pilot.config["localTest"]:
        raise ValueError("Guard probes require synthetic local-test configuration")
    pilot.database_preflight(empty=True)
    suffix = uuid.uuid4().hex
    for schema in ("public", "extensions"):
        table = schema + ".restore_guard_" + suffix
        pilot.sql("CREATE TABLE " + table + "(id integer)")
        try:
            rejects(lambda: pilot.database_preflight(empty=True))
        finally:
            pilot.sql("DROP TABLE " + table)
    schema = "restore_guard_" + suffix
    pilot.sql("CREATE SCHEMA " + schema)
    try:
        rejects(lambda: pilot.database_preflight(empty=True))
    finally:
        pilot.sql("DROP SCHEMA " + schema)
    pilot.database_preflight(empty=True)
    return {"nonemptyPublicExtensionCustomSchemaRejected": "pass"}


def probe(pilot, archive, cert, key):
    checks = empty_database_guard(pilot)
    pilot.database_preflight(empty=True)
    # Missing expected global role must fail before archive SQL.
    pilot.sql("ALTER ROLE eaap_identity_operator RENAME TO eaap_missing_role_probe")
    try:
        rejects(lambda: backup.restore(pilot, archive, cert, key))
    finally:
        pilot.sql("ALTER ROLE eaap_missing_role_probe RENAME TO eaap_identity_operator")
    pilot.database_preflight(empty=True)
    checks["missingTargetRoleRejected"] = "pass"
    with backup.secure_tmp("/dev/shm") as directory:
        directory = Path(directory)
        package = directory / "original.tar"
        backup.decrypt(archive, package, cert, key)
        manifest, dump = backup.unpack(package, directory)
        # This is a trusted synthetic corruption fixture, not untrusted archive restore.
        # Retain custom archive TOC but interrupt data transport near the end.
        truncated = directory / "interrupted.dump"
        raw = dump.read_bytes()
        truncated.write_bytes(raw[:-4096])
        truncated.chmod(0o600)
        backup.pg_stream(pilot, ["pg_restore", "--list"], truncated)
        manifest["dumpSha256"] = backup.sha(truncated)
        payload = directory / "interrupted.tar"
        backup.pack(truncated, manifest, payload)
        broken = directory / "interrupted.cms"
        backup.encrypt(payload, broken, cert)
        rejects(lambda: backup.restore(pilot, broken, cert, key))
        pilot.database_preflight(empty=True)
        checks["interruptedRestoreTransactionRolledBack"] = "pass"
        # A manifest that identifies this destination as source must be refused.
        manifest["dumpSha256"] = backup.sha(dump)
        manifest["sourceCluster"] = pilot.database_preflight(empty=True)["clusterId"]
        payload = directory / "same-cluster.tar"
        backup.pack(dump, manifest, payload)
        same = directory / "same-cluster.cms"
        backup.encrypt(payload, same, cert)
        rejects(lambda: backup.restore(pilot, same, cert, key))
        pilot.database_preflight(empty=True)
        checks["sourceClusterRestoreRejected"] = "pass"
    return checks
