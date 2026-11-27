"""Authenticated encrypted PostgreSQL backups and fail-closed isolated restores."""

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import tarfile
import tempfile
import time
import uuid
from datetime import UTC, datetime
from pathlib import Path

from deploy.scripts.pilot import OperationError, Pilot, checked, private_file

FORMAT = "CMS-AuthEnvelopedData-AES-256-GCM-RSA-OAEP-SHA256-v1"
LIMIT = (
    1024 * 1024 * 1024
)  # Small pilot archive bound, not an unbounded export service.


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def secure_tmp(parent):
    parent = Path(parent).resolve(strict=True)
    # Temporary decrypted data must stay on explicitly selected memory-backed storage.
    mounts = []
    for line in Path("/proc/self/mountinfo").read_text().splitlines():
        before, after = line.split(" - ", 1)
        mount = Path(before.split()[4].replace("\\040", " "))
        if parent.is_relative_to(mount):
            mounts.append((len(str(mount)), after.split()[0]))
    if not mounts or max(mounts)[1] != "tmpfs":
        raise OperationError("Backup plaintext workspace must be on a Linux tmpfs")
    return tempfile.TemporaryDirectory(prefix="eaap-backup-", dir=parent)


def archive_policy(path):
    # Require OpenSSL to identify the authenticated container and cipher.
    process = subprocess.Popen(
        ["openssl", "cms", "-cmsout", "-print", "-inform", "DER", "-in", str(path)],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    try:
        prefix = process.stdout.read(8192)
    finally:
        process.terminate()
        process.communicate(timeout=10)
    if (
        b"id-smime-ct-authEnvelopedData" not in prefix
        or b"aes-256-gcm" not in prefix
        or b"rsaesOaep" not in prefix
    ):
        raise OperationError("Unsupported authenticated backup encryption format")


def encrypt(source, output, certificate):
    private_file(certificate)
    with Path(source).open("rb") as stream, Path(output).open("xb") as target:
        os.chmod(output, 0o600)
        try:
            result = subprocess.run(
                [
                    "openssl",
                    "cms",
                    "-encrypt",
                    "-binary",
                    "-stream",
                    "-aes-256-gcm",
                    "-outform",
                    "DER",
                    "-recip",
                    str(certificate),
                    "-keyopt",
                    "rsa_padding_mode:oaep",
                    "-keyopt",
                    "rsa_oaep_md:sha256",
                ],
                stdin=stream,
                stdout=target,
                stderr=subprocess.PIPE,
                check=False,
                timeout=300,
            )
        except BaseException:
            target.close()
            Path(output).unlink(missing_ok=True)
            raise

    if result.returncode:
        Path(output).unlink(missing_ok=True)
        raise OperationError("Backup encryption failed")


def decrypt(source, output, certificate, key):
    private_file(key)
    private_file(certificate)
    if not 0 < Path(source).stat().st_size <= LIMIT:
        raise OperationError("Encrypted backup size invalid")
    archive_policy(source)
    with Path(output).open("xb") as target:
        os.chmod(output, 0o600)
        try:
            result = subprocess.run(
                [
                    "openssl",
                    "cms",
                    "-decrypt",
                    "-binary",
                    "-inform",
                    "DER",
                    "-in",
                    str(source),
                    "-recip",
                    str(certificate),
                    "-inkey",
                    str(key),
                ],
                stdout=target,
                stderr=subprocess.PIPE,
                check=False,
                timeout=300,
            )
        except BaseException:
            target.close()
            Path(output).unlink(missing_ok=True)
            raise

    if result.returncode:
        Path(output).unlink(missing_ok=True)
        raise OperationError("Backup authentication/decryption failed")
    # No destination database is contacted before authenticated decryption completes.


def pack(dump, manifest, target):
    content = json.dumps(manifest, sort_keys=True).encode()
    with tarfile.open(target, "w") as archive:
        info = tarfile.TarInfo("manifest.json")
        info.size = len(content)
        info.mode = 0o600
        archive.addfile(info, io.BytesIO(content))
        info = tarfile.TarInfo("database.dump")
        info.size = Path(dump).stat().st_size
        info.mode = 0o600
        with Path(dump).open("rb") as stream:
            archive.addfile(info, stream)
    os.chmod(target, 0o600)


def unpack(source, directory):
    with tarfile.open(source, "r:") as archive:
        members = []
        while member := archive.next():
            members.append(member)
            if len(members) > 2:
                raise OperationError("Unexpected backup contents")
        if (
            len(members) != 2
            or {m.name for m in members} != {"manifest.json", "database.dump"}
            or any(not m.isfile() or m.size > LIMIT for m in members)
        ):
            raise OperationError("Unexpected backup contents")
        member = archive.getmember("manifest.json")
        if member.size > 16384:
            raise OperationError("Backup manifest limit")
        manifest = json.load(archive.extractfile(member))
        target = Path(directory) / "restore.dump"
        with (
            target.open("xb") as output,
            archive.extractfile("database.dump") as stream,
        ):
            os.chmod(target, 0o600)
            while chunk := stream.read(1024 * 1024):
                output.write(chunk)
    if (
        manifest.get("format") != FORMAT
        or manifest.get("schemaVersion") not in ("8", "9")
        or manifest.get("postgresMajor") != 17
        or manifest.get("vectorVersion") != "0.8.7"
        or sha(target) != manifest.get("dumpSha256")
    ):
        raise OperationError(
            "Backup compatibility or plaintext archive integrity failed"
        )
    return manifest, target


def create(pilot, certificate, output_dir, temporary="/dev/shm"):
    pilot.preflight()
    state = pilot.database_preflight()
    output_dir = Path(output_dir)
    if not output_dir.exists():
        output_dir.mkdir(mode=0o700, parents=True)
    permissions = output_dir.stat().st_mode
    if (
        output_dir.is_symlink()
        or not output_dir.is_dir()
        or permissions & 0o077
        or permissions & 0o700 != 0o700
    ):
        raise OperationError("Backup destination must be owner-only and writable")
    backup_id = "backup-" + uuid.uuid4().hex
    final = output_dir / (backup_id + ".cms")
    partial = output_dir / (backup_id + ".partial")
    started = time.monotonic()
    try:
        with secure_tmp(temporary) as directory:
            directory = Path(directory)
            dump = directory / "database.dump"
            with dump.open("xb") as target:
                os.chmod(dump, 0o600)
                process = subprocess.run(
                    pilot.command
                    + [
                        "exec",
                        "-T",
                        "postgres",
                        "pg_dump",
                        "-U",
                        "eaap_dev",
                        "-d",
                        "eaap_pilot",
                        "--format=custom",
                        "--no-password",
                        "--lock-wait-timeout=5s",
                    ],
                    env=pilot.env,
                    stdout=target,
                    stderr=subprocess.PIPE,
                    check=False,
                    timeout=300,
                )
            if process.returncode or not 0 < dump.stat().st_size <= LIMIT:
                raise OperationError(
                    "Database export failed or exceeded pilot archive bound"
                )
            manifest = dict(
                format=FORMAT,
                backupId=backup_id,
                createdAt=datetime.now(UTC).isoformat(),
                postgresMajor=state["postgresMajor"],
                vectorVersion=state["vectorVersion"],
                schemaVersion=state["migration"],
                sourceCluster=state["clusterId"],
                sourceProject=pilot.project,
                applicationRelease=pilot.config["release"],
                dumpSha256=sha(dump),
                toolVersion=checked(["openssl", "version"]).decode().strip(),
            )
            package = directory / "payload.tar"
            pack(dump, manifest, package)
            encrypt(package, partial, certificate)
            # Publishing is no-overwrite even under concurrent invocations.
            os.link(partial, final)
            partial.unlink()
        metadata = dict(
            backupId=backup_id,
            createdAt=manifest["createdAt"],
            postgresMajor=17,
            schemaVersion=manifest["schemaVersion"],
            vectorVersion="0.8.7",
            bytes=final.stat().st_size,
            sha256=sha(final),
            format=FORMAT,
            toolVersion=manifest["toolVersion"],
            durationSeconds=time.monotonic() - started,
        )
        with final.with_suffix(".json").open("x") as stream:
            json.dump(metadata, stream, indent=2)
        os.chmod(final.with_suffix(".json"), 0o600)
        return {"archive": str(final), **metadata}
    finally:
        partial.unlink(missing_ok=True)


def validate(archive, certificate, key, temporary="/dev/shm"):
    with secure_tmp(temporary) as directory:
        payload = Path(directory) / "authenticated.tar"
        decrypt(archive, payload, certificate, key)
        manifest, _ = unpack(payload, directory)
        return {
            "backupId": manifest["backupId"],
            "authentication": "verified",
            "dumpIntegrity": "verified",
            "postgresMajor": manifest["postgresMajor"],
            "vectorVersion": manifest["vectorVersion"],
            "schemaVersion": manifest["schemaVersion"],
        }


def pg_stream(pilot, args, source):
    with Path(source).open("rb") as stream:
        result = subprocess.run(
            pilot.command + ["exec", "-T", "postgres", *args],
            env=pilot.env,
            stdin=stream,
            capture_output=True,
            check=False,
            timeout=600,
        )
    if result.returncode or len(result.stdout) > 1024 * 1024:
        raise OperationError("PostgreSQL archive validation or restore failed")
    return result.stdout


def restore(pilot, archive, certificate, key, temporary="/dev/shm"):
    pilot.preflight()
    started = time.monotonic()
    with secure_tmp(temporary) as directory:
        payload = Path(directory) / "authenticated.tar"
        decrypt(archive, payload, certificate, key)
        manifest, dump = unpack(payload, directory)
        target = pilot.database_preflight(empty=True)
        if (
            target["clusterId"] == manifest["sourceCluster"]
            or pilot.project == manifest["sourceProject"]
        ):
            raise OperationError(
                "Restore destination must be independent of the source"
            )
        running = (
            pilot.compose(["ps", "--status", "running", "--services"]).decode().split()
        )
        if set(running) != {"postgres"}:
            raise OperationError(
                "Restore requires an isolated database with application "
                "services stopped"
            )
        toc = pg_stream(pilot, ["pg_restore", "--list"], dump).decode()
        # Bootstrap owns initial schemas and the installed exact extension. Preserve
        # all data, table/function ownership, RLS, grants and default privileges.
        entries = []
        for line in toc.splitlines():
            if re.search(r" SCHEMA - (platform|public|extensions) ", line) or re.search(
                r" EXTENSION - vector ", line
            ):
                continue
            entries.append(line)
        listing = Path(directory) / "restore.list"
        listing.write_text("\n".join(entries) + "\n")
        target_list = "/tmp/" + uuid.uuid4().hex + ".list"
        try:
            pg_stream(
                pilot,
                ["sh", "-c", 'umask 077; cat > "$1"', "operator", target_list],
                listing,
            )
            pg_stream(
                pilot,
                [
                    "pg_restore",
                    "--exit-on-error",
                    "--single-transaction",
                    "--no-password",
                    "--use-list=" + target_list,
                    "-U",
                    "eaap_dev",
                    "-d",
                    "eaap_pilot",
                ],
                dump,
            )
        finally:
            pilot.compose(["exec", "-T", "postgres", "rm", "-f", target_list])
        pilot.database_preflight(expected_schema=manifest["schemaVersion"])
    return {
        "backupId": manifest["backupId"],
        "restore": "completed",
        "durationSeconds": time.monotonic() - started,
        "destinationProject": pilot.project,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["create", "validate", "restore"])
    parser.add_argument("--config")
    parser.add_argument("--archive")
    parser.add_argument("--certificate", required=True)
    parser.add_argument("--key")
    parser.add_argument("--output")
    parser.add_argument("--temporary", default="/dev/shm")
    args = parser.parse_args()
    try:
        if args.command == "create":
            result = create(
                Pilot(args.config), args.certificate, args.output, args.temporary
            )
        elif args.command == "validate":
            result = validate(args.archive, args.certificate, args.key, args.temporary)
        else:
            result = restore(
                Pilot(args.config),
                args.archive,
                args.certificate,
                args.key,
                args.temporary,
            )
        print(json.dumps(result))
        return 0
    except (
        OperationError,
        OSError,
        ValueError,
        KeyError,
        TypeError,
        tarfile.TarError,
        subprocess.SubprocessError,
    ):
        print(
            "Backup operation failed; authentication, compatibility "
            "and target checks must pass. Private diagnostics omitted."
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
