"""Synthetic HTTPS pilot/recovery drill. Explicit fresh local projects only."""

import argparse
import hashlib
import json
import shutil
import ssl
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

from deploy.scripts import backup
from deploy.scripts.failures import running_failures, startup_without_database
from deploy.scripts.identity_ingress import probe as identity_probe
from deploy.scripts.pilot import (
    OperationError,
    Pilot,
    checked,
    init_local,
    private_file,
)
from deploy.scripts.recovery_failures import probe
from evaluation.environment import Lab, NoRedirect


class Client(Lab):
    # Reuse the existing synthetic API fixtures, without its dev Compose lifecycle.
    def __init__(self, pilot, tenant):
        self.pilot = pilot
        self.tokens = []
        self.tenant = tenant
        self.prefix = "/tenants/" + tenant
        self.url = f"https://127.0.0.1:{pilot.config['port']}/api/v1"
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            NoRedirect(),
            urllib.request.HTTPSHandler(
                context=ssl.create_default_context(
                    cafile=str(Path(pilot.config["secretDir"]) / "tls.crt")
                )
            ),
        )
        self.token = ""


def bootstrap(pilot, tenant, user, email):
    pilot.operator(
        "identity-bootstrap",
        {
            "BOOTSTRAP_TENANT_ID": tenant,
            "BOOTSTRAP_USER_ID": user,
            "BOOTSTRAP_EMAIL": email,
            "BOOTSTRAP_TENANT_NAME": "Synthetic pilot only",
        },
    )


def start_database(pilot):
    pilot.preflight()
    pilot.compose(["up", "-d", "--wait", "postgres"])
    pilot.sql("\\i /opt/eaap/knowledge-extension.sql")


def snapshot(pilot):
    tables = pilot.sql(
        "SELECT tablename FROM pg_tables WHERE schemaname='platform' ORDER BY tablename"
    ).splitlines()
    state = {}
    for table in tables:
        # Sort catalog-generated table rows for deterministic comparison.
        if not table.replace("_", "").isalnum():
            raise ValueError("Unexpected table name")
        raw = pilot.sql(
            f"SELECT row_to_json(t)::text FROM platform.{table} t "
            "ORDER BY row_to_json(t)::text"
        )
        state[table] = {
            "rows": len(raw.splitlines()),
            "sha256": hashlib.sha256(raw.encode()).hexdigest(),
        }
    return state


def fixture(client):
    ap = client.prefix + "/agents"
    agent = client.api(
        "POST",
        ap,
        {
            "name": "Synthetic pilot admissions",
            "systemInstructions": "Use authorized evidence only.",
        },
        expected=201,
    )
    client.agent = agent["id"]
    client.agent_path = ap + "/" + client.agent
    admin = client.invite("manager@example.test", "ADMIN")
    member = client.invite("member@example.test", "MEMBER")
    doc = client.upload(
        "synthetic-admissions.txt",
        b"Applicants must submit a valid passport and academic transcripts. "
        b"The synthetic deadline is 30 September.",
    )
    result = client.api(
        "POST",
        client.agent_path + "/knowledge/search",
        {"query": "Which admissions documents are required?", "topK": 3},
    )
    assert doc["id"] in json.dumps(result) and "passport" in json.dumps(result).lower()
    cid = client.api(
        "POST",
        client.agent_path + "/conversations",
        {"title": "Synthetic recovery history"},
        expected=201,
    )["id"]
    cp = client.agent_path + "/conversations/" + cid
    body = {
        "message": "Which documents are required?",
        "requestId": str(uuid.uuid4()),
    }
    run = client.api("POST", cp + "/messages", body, expected=200)
    # Provider disabled is a truthful durable failure, never invented model output.
    assert "MODEL_UNAVAILABLE" in json.dumps(run)
    replay = client.api("POST", cp + "/messages", body, expected=200)
    assert run["runId"] == replay["runId"]
    payload = {
        "toolName": "admissions.create_followup_task",
        "arguments": {
            "title": "Synthetic followup",
            "description": "Recovery drill only",
            "priority": "NORMAL",
        },
        "rationale": "Human approval drill",
    }
    client.api(
        "POST",
        client.agent_path + "/tool-proposals",
        payload,
        token=member,
        expected=403,
    )
    proposal = client.api(
        "POST", client.agent_path + "/tool-proposals", payload, expected=201
    )
    pp = client.prefix + "/tool-proposals/" + proposal["id"]
    assert (
        client.api("GET", client.prefix + "/admissions/followup-tasks")["items"] == []
    )
    approval = {"expectedPayloadDigest": proposal["payloadDigest"]}
    client.api("POST", pp + "/approve", approval, expected=403)
    client.api("POST", pp + "/execute", expected=409)
    client.api("POST", pp + "/approve", approval, token=admin)
    assert (
        client.api("GET", client.prefix + "/admissions/followup-tasks")["items"] == []
    )
    task = client.api("POST", pp + "/execute")
    again = client.api("POST", pp + "/execute")
    assert task["taskId"] == again["taskId"]
    tasks = client.api("GET", client.prefix + "/admissions/followup-tasks")["items"]
    assert len(tasks) == 1 and tasks[0]["status"] == "OPEN"
    client.api(
        "GET",
        "/tenants/" + str(uuid.uuid4()) + "/knowledge/documents/" + doc["id"],
        expected=403,
    )
    return dict(
        agent=client.agent,
        document=doc["id"],
        conversation=cid,
        proposal=proposal["id"],
        task=task["taskId"],
    )


def check_private_logs(pilot, tokens, password):
    logs = pilot.compose(["logs", "--no-color", "--tail", "2000"]).decode(
        errors="replace"
    )
    sensitive = list(tokens) + [
        password,
        "Applicants must submit a valid passport and academic transcripts.",
        "Which documents are required?",
    ]
    for name in (
        "DB_PASSWORD",
        "MIGRATION_DB_PASSWORD",
        "POSTGRES_PASSWORD",
        "IDENTITY_OPERATOR_DB_PASSWORD",
        "EMBEDDING_SECRET",
        "EXECUTION_INTERNAL_SECRET",
        "OPERATIONS_SECRET",
    ):
        sensitive.append(private_file(Path(pilot.config["secretDir"]) / name).decode())
    assert not any(value and value in logs for value in sensitive)
    assert "BEGIN PRIVATE KEY" not in logs


def drill(source_config, destination_directory, report):
    started = time.monotonic()
    source = Pilot(source_config)
    if not source.config["localTest"]:
        raise ValueError("Acceptance requires a synthetic local-test config")
    existing = checked(
        [
            "docker",
            "ps",
            "-aq",
            "--filter",
            "label=com.docker.compose.project=" + source.project,
        ]
    )
    volumes = checked(
        [
            "docker",
            "volume",
            "ls",
            "-q",
            "--filter",
            "label=com.docker.compose.project=" + source.project,
        ]
    )
    if existing.strip() or volumes.strip():
        raise ValueError("Acceptance requires an unused uniquely named source project")
    destination = None
    evidence = {"paidProviderCalls": 0, "checks": {}}
    try:
        startup_without_database(source)
        evidence["checks"]["databaseAbsentAtStartup"] = "pass"
        start_database(source)
        source.database_preflight(empty=True)
        source.operator("migration")
        tenant, user = str(uuid.uuid4()), str(uuid.uuid4())
        bootstrap(source, tenant, user, "owner@example.test")
        other_tenant = str(uuid.uuid4())
        bootstrap(source, other_tenant, str(uuid.uuid4()), "other@example.test")
        source.compose(["up", "-d", "--wait"])
        client = Client(source, tenant)
        password = (
            private_file(Path(source.config["secretDir"]) / "BOOTSTRAP_PASSWORD")
            .decode()
            .strip()
        )
        client.token = client.login("owner@example.test", password)
        records = fixture(client)
        evidence["checks"]["httpsBusinessFlows"] = "pass"
        check_private_logs(source, client.tokens, password)
        evidence["checks"]["sourceLogRedaction"] = "pass"
        evidence["identityIngress"] = identity_probe(source, password)
        check_private_logs(source, client.tokens, password)
        before = snapshot(source)
        source_state = source.database_preflight()
        certificate_dir = Path(source_config).parent / "offline-backup-key"
        certificate_dir.mkdir(mode=0o700)
        key, cert = certificate_dir / "recipient.key", certificate_dir / "recipient.crt"
        checked(
            [
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:3072",
                "-nodes",
                "-days",
                "3",
                "-subj",
                "/CN=synthetic-backup",
                "-keyout",
                str(key),
                "-out",
                str(cert),
            ]
        )
        cert.chmod(0o600)
        exported = backup.create(source, cert, Path(source_config).parent / "backups")
        backup.validate(exported["archive"], cert, key)
        evidence["backup"] = {
            k: exported[k] for k in ("bytes", "sha256", "durationSeconds", "format")
        }
        target_path = init_local(
            destination_directory, source.config["modelDir"], source.config["port"] + 1
        )
        destination_config = json.loads(target_path.read_bytes())
        shutil.copyfile(source.config["imageLock"], destination_config["imageLock"])
        destination = Pilot(target_path)
        source.compose(["stop"])
        start_database(destination)
        evidence["checks"].update(probe(destination, exported["archive"], cert, key))
        restored = backup.restore(destination, exported["archive"], cert, key)
        evidence["restoreSeconds"] = restored["durationSeconds"]
        after = snapshot(destination)
        assert before == after
        assert (
            source_state["clusterId"] != destination.database_preflight()["clusterId"]
        )
        evidence["tables"] = after
        evidence["checks"]["independentClusterExactRows"] = "pass"
        try:
            backup.restore(destination, exported["archive"], cert, key)
        except OperationError as error:
            assert "nonempty" in str(error)
        else:
            raise AssertionError("Nonempty restore was accepted")
        destination.operator("migration")
        destination.compose(["up", "-d", "--wait"])
        recovered = Client(destination, tenant)
        recovered.api(
            "GET", recovered.prefix + "/context", token=client.token, expected=401
        )
        recovered.token = recovered.login("owner@example.test", password)
        assert recovered.api("GET", recovered.prefix + "/context")["role"] == "OWNER"
        recovered.agent = records["agent"]
        recovered.agent_path = recovered.prefix + "/agents/" + recovered.agent
        doc = recovered.api(
            "GET", recovered.prefix + "/knowledge/documents/" + records["document"]
        )
        assert doc["status"] == "READY" and doc["chunkCount"] > 0
        result = recovered.api(
            "POST",
            recovered.agent_path + "/knowledge/search",
            {"query": "passport transcripts", "topK": 3},
        )
        assert records["document"] in json.dumps(result)
        recovered.api(
            "GET",
            "/tenants/" + other_tenant + "/knowledge/documents/" + records["document"],
            expected=403,
        )
        tasks = recovered.api("GET", recovered.prefix + "/admissions/followup-tasks")[
            "items"
        ]
        assert len(tasks) == 1 and tasks[0]["id"] == records["task"]
        replayed = recovered.api(
            "POST",
            recovered.prefix + "/tool-proposals/" + records["proposal"] + "/execute",
        )
        assert replayed["taskId"] == records["task"]
        history = recovered.api(
            "GET",
            recovered.agent_path
            + "/conversations/"
            + records["conversation"]
            + "/messages",
        )
        assert len(history["items"]) == 2
        evidence["checks"]["restoredAuthenticationKnowledgeHistoryApprovals"] = "pass"
        evidence["checks"].update(running_failures(destination, recovered))
        check_private_logs(destination, recovered.tokens + client.tokens, password)
        evidence["checks"]["restoredLogRedaction"] = "pass"
        evidence["operations"] = destination.diagnostics()
        evidence["durationSeconds"] = time.monotonic() - started
        with Path(report).open("x") as output:
            output.write(json.dumps(evidence, indent=2) + "\n")
        return evidence
    finally:
        # Remove only this drill's containers/networks; preserve data volumes/evidence.
        source.compose(["down", "--remove-orphans"])
        if destination:
            destination.compose(["down", "--remove-orphans"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-config", required=True)
    parser.add_argument("--destination-directory", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    drill(args.source_config, args.destination_directory, args.report)
    print("Synthetic pilot recovery drill passed; report contains no credentials.")


if __name__ == "__main__":
    main()
