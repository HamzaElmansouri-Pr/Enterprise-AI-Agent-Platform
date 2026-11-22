"""Failure probes limited to the explicit synthetic pilot used by acceptance."""

import json
import time
import urllib.error
import urllib.request

from deploy.scripts.pilot import OperationError, checked


def health(pilot, endpoint):
    output = pilot.compose(
        [
            "exec",
            "-T",
            "control-plane",
            "curl",
            "--silent",
            "--max-time",
            "6",
            "-w",
            "\n%{http_code}",
            "http://127.0.0.1:9090/actuator/health/" + endpoint,
        ]
    )
    raw, code = output.decode().rsplit("\n", 1)
    return int(code), json.loads(raw)["status"]


def wait_health(pilot, endpoint, expected):
    for _ in range(50):
        try:
            if health(pilot, endpoint) == expected:
                return
        except (OperationError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError("Pilot health did not reach expected bounded state")


def rejected_start(pilot, variable):
    try:
        pilot.compose(
            ["run", "--rm", "--no-deps", "-e", variable, "control-plane"], timeout=90
        )
    except OperationError:
        return
    raise AssertionError("Unsafe startup was accepted")


def startup_without_database(pilot):
    name = pilot.project + "-startup-probe"
    pilot.compose(["run", "--no-deps", "-d", "--name", name, "control-plane"])
    try:
        for _ in range(60):
            try:
                raw = checked(
                    [
                        "docker",
                        "exec",
                        name,
                        "curl",
                        "--silent",
                        "--max-time",
                        "4",
                        "-w",
                        "\n%{http_code}",
                        "http://127.0.0.1:9090/actuator/health/readiness",
                    ]
                )
                if raw.endswith(b"\n503"):
                    alive = checked(
                        [
                            "docker",
                            "exec",
                            name,
                            "curl",
                            "--fail",
                            "--silent",
                            "--max-time",
                            "4",
                            "http://127.0.0.1:9090/actuator/health/liveness",
                        ]
                    )
                    assert json.loads(alive)["status"] == "UP"
                    return
            except OperationError:
                pass
            time.sleep(1)
        raise AssertionError("Startup without database did not become unready")
    finally:
        checked(["docker", "stop", "--time", "10", name])
        checked(["docker", "rm", name])


def running_failures(pilot, client):
    checks = {}
    wait_health(pilot, "readiness", (200, "UP"))
    wait_health(pilot, "liveness", (200, "UP"))
    # Probe ingress using the client CA: actual certificate validation stays enabled.
    base = client.url.removesuffix("/api/v1")
    with client.opener.open(base + "/", timeout=5) as response:
        assert b"<html" in response.read().lower()
        assert response.headers["Strict-Transport-Security"]
        assert "script-src 'self'" in response.headers["Content-Security-Policy"]
    for path in ("/actuator/health", "/internal/operations", "/ready", "/health"):
        try:
            client.opener.open(base + path, timeout=5)
        except urllib.error.HTTPError as error:
            assert error.code == 404
        else:
            raise AssertionError("Private endpoint exposed through ingress")
    cid = pilot.compose(["ps", "-q", "postgres"]).decode().strip()
    ports = json.loads(
        checked(
            ["docker", "inspect", cid, "--format", "{{json .HostConfig.PortBindings}} "]
        )
    )
    assert not ports
    checks["httpsIngressHeadersPrivateEndpoints"] = "pass"
    rejected_start(pilot, "EMBEDDING_SECRET=")
    rejected_start(pilot, "AUTH_PUBLIC_KEY=file:/run/secrets/jwt-private.pem")
    checks["missingSecretInvalidJwtRejected"] = "pass"
    # A wrong migration credential must fail, without changing migration history.
    try:
        pilot.operator(
            "migration", {"MIGRATION_DB_PASSWORD": "invalid-synthetic-credential"}
        )
    except OperationError:
        pass
    else:
        raise AssertionError("Wrong migration credential accepted")
    pilot.database_preflight()
    checks["failedMigrationPreservesSchema"] = "pass"
    pilot.compose(["stop", "agent-runtime"])
    assert client.api("GET", client.prefix + "/context")["role"] == "OWNER"
    # Core readiness is deliberately independent of optional inference availability.
    wait_health(pilot, "readiness", (200, "UP"))
    pilot.compose(["up", "-d", "--wait", "agent-runtime"])
    checks["runtimeOutageCoreIdentityAvailable"] = "pass"
    pilot.compose(["stop", "postgres"])
    wait_health(pilot, "readiness", (503, "DOWN"))
    wait_health(pilot, "liveness", (200, "UP"))
    # Observe actual Docker unhealthy status; readiness alone is a different signal.
    container = pilot.compose(["ps", "-q", "control-plane"]).decode().strip()
    for _ in range(130):
        status = (
            checked(
                [
                    "docker",
                    "inspect",
                    container,
                    "--format",
                    "{{.State.Health.Status}} ",
                ]
            )
            .decode()
            .strip()
        )
        if status == "unhealthy":
            break
        time.sleep(1)
    else:
        raise AssertionError("Dependency outage did not mark container unhealthy")
    checks["unhealthyContainerObserved"] = "pass"
    pilot.compose(["up", "-d", "--wait", "postgres"])
    wait_health(pilot, "readiness", (200, "UP"))
    assert client.api("GET", client.prefix + "/context")["role"] == "OWNER"
    checks["databaseInterruptionReadinessRecovery"] = "pass"
    without_context = pilot.sql(
        "SELECT count(*) FROM platform.agent_configurations",
        user="eaap_runtime",
        password_file="/run/secrets/DB_PASSWORD",
    )
    assert without_context == "0"
    checks["runtimeForcedRlsWithoutContext"] = "pass"
    pilot.compose(["restart", "control-plane"])
    wait_health(pilot, "readiness", (200, "UP"))
    assert client.api("GET", client.prefix + "/context")["role"] == "OWNER"
    checks["controlledRestartPersistentIdentity"] = "pass"
    return checks
