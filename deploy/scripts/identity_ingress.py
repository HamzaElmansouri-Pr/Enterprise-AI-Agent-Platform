"""Bounded synthetic TCP-origin experiment, only inside the owned pilot drill."""

import json
import math
import statistics
import time
from collections import Counter
from pathlib import Path

from deploy.scripts.pilot import checked, private_file

# A helper shares only web's network namespace, not its files/secrets/processes.
# Linux loopback /8 lets ordinary non-root sockets bind reproducible source identities.
# TLS connections verify the local certificate and record their socket source.
CLIENT = (Path(__file__).with_name("ingress_client.py")).read_text()

NETWORK = r"""
import json,socket,sys
p=json.load(sys.stdin);results={}
for name,host,port in p:
    try:
        with socket.create_connection((host,port),timeout=1):results[name]="reachable"
    except OSError:results[name]="denied"
print(json.dumps(results))
"""


def container(pilot, service):
    return pilot.compose(["ps", "-q", service]).decode().strip()


def inspect(identifier):
    return json.loads(checked(["docker", "inspect", identifier]))[0]


def helper(pilot, network, code, payload):
    return json.loads(
        checked(
            [
                "docker",
                "run",
                "--rm",
                "-i",
                "--network",
                network,
                "--user",
                "10001:10001",
                "--read-only",
                "--cap-drop",
                "ALL",
                "--security-opt",
                "no-new-privileges:true",
                "--memory",
                "128m",
                "--cpus",
                "0.5",
                "--entrypoint",
                "python",
                pilot.images["agent-runtime"],
                "-c",
                code,
            ],
            data=json.dumps(payload).encode(),
            timeout=120,
        )
    )


def restart(pilot, service):
    pilot.compose(["restart", service])
    pilot.compose(["up", "-d", "--wait", "--no-deps", service])


def probe(pilot, password):
    if not pilot.config["localTest"]:
        raise ValueError("Identity experiment requires synthetic local-test ownership")
    ca = private_file(Path(pilot.config["secretDir"]) / "tls.crt").decode()
    samples = []

    def run(requests, parallel=False):
        result = helper(
            pilot,
            "container:" + container(pilot, "web"),
            CLIENT,
            dict(ca=ca, requests=requests, parallel=parallel),
        )
        samples.extend(result)
        return result

    def req(
        source,
        email="owner@example.test",
        supplied=None,
        headers=None,
        body=None,
        method="POST",
        path="/api/v1/auth/login",
    ):
        return [
            source,
            body
            if body is not None
            else json.dumps(
                dict(email=email, password=password if supplied is None else supplied)
            ),
            {"Content-Type": "application/json", **(headers or {})},
            method,
            path,
        ]

    restart(pilot, "control-plane")
    restart(pilot, "web")
    # Six legitimate logins, two accounts, one TCP source through one proxy.
    legitimate = run(
        [
            req("127.0.0.2", "owner@example.test" if n % 2 else "other@example.test")
            for n in range(6)
        ]
    )
    assert all(r["status"] == 200 and r["validToken"] for r in legitimate)
    # Finish A's ingress burst with malformed requests (no expensive hashes).
    run([req("127.0.0.2", body="{}") for _ in range(4)])
    spoof_headers = [
        {"X-Forwarded-For": "203.0.113.7"},
        {"X-Real-IP": "203.0.113.8"},
        {"Forwarded": "for=203.0.113.9;proto=https"},
        {"X-Forwarded-Host": "forged.invalid"},
        {"X-Forwarded-Proto": "http"},
        {"X-Client-Identity": "different"},
    ]
    forged = run([req("127.0.0.2", headers=h) for h in spoof_headers])
    assert all(r["status"] == 429 and r["layer"] == "ingress" for r in forged)
    independent = run([req("127.0.0.3")])[0]
    assert independent["status"] == 200 and independent["source"] == "127.0.0.3"
    # Unrelated static requests and GET login are outside the POST limit.
    static = run([req("127.0.0.2", method="GET", path="/", body="") for _ in range(12)])
    assert all(r["status"] == 200 for r in static)
    assert run([req("127.0.0.2", method="GET", body="")])[0]["status"] != 429
    restart(pilot, "web")
    assert run([req("127.0.0.2", body="{}")])[0]["status"] == 400

    # An ingress-only namespace must not reach private service addresses.
    web = inspect(container(pilot, "web"))
    ingress = next(
        n for n in web["NetworkSettings"]["Networks"] if n.endswith("_ingress")
    )
    targets = []
    for service, port in [
        ("control-plane", 8080),
        ("control-plane", 9090),
        ("agent-runtime", 8000),
        ("postgres", 5432),
    ]:
        settings = inspect(container(pilot, service))["NetworkSettings"]["Networks"]
        ip = next(iter(settings.values()))["IPAddress"]
        targets.append([f"{service}:{port}", ip, port])
    denied = helper(pilot, ingress, NETWORK, targets)
    assert all(value == "denied" for value in denied.values())

    # Different real TCP sources cannot bypass Spring's process-global budget.
    restart(pilot, "control-plane")
    remaining = 60 - time.time() % 60
    if remaining < 5:
        time.sleep(remaining + 0.1)
    global_budget = run([req(f"127.0.1.{n + 1}", body="{}") for n in range(21)])
    assert [r["status"] for r in global_budget] == [400] * 20 + [429]
    assert global_budget[-1]["layer"] == "application"
    restart(pilot, "control-plane")
    assert run([req("127.0.2.1")])[0]["status"] == 200
    wrong = run(
        [
            req("127.0.2.2", supplied="synthetic-wrong-password"),
            req("127.0.2.3", email="unknown@example.test"),
        ]
    )
    assert [r["status"] for r in wrong] == [401, 401]
    legitimate_parallel = run(
        [req("127.0.2.4"), req("127.0.2.5", email="other@example.test")],
        parallel=True,
    )
    assert all(r["status"] == 200 and r["validToken"] for r in legitimate_parallel)
    same_identity = run([req("127.0.4.1"), req("127.0.4.2")], parallel=True)
    assert sorted(r["status"] for r in same_identity) == [200, 429]
    concurrent = run(
        [
            req(
                f"127.0.3.{n + 1}",
                "owner@example.test"
                if n == 0
                else ("other@example.test" if n == 1 else f"unknown{n}@example.test"),
            )
            for n in range(8)
        ],
        parallel=True,
    )
    assert all(r["status"] in (200, 401, 429) for r in concurrent)
    assert any(r["status"] in (200, 401) for r in concurrent)
    counters = pilot.diagnostics()["counters"]
    assert counters["LOGIN_HASH_PEAK"] == 2
    assert counters["LOGIN_IDENTITY_BUSY"] > 0 and counters["LOGIN_HASH_BUSY"] > 0
    durations = sorted(r["ms"] for r in samples)
    return dict(
        topology=(
            "Non-root helper sharing only Nginx network namespace; "
            "bound loopback /8 TCP sources, verified TLS. Separate ingress-only "
            "helper tests private network denial. No forwarded identity or "
            "random-address assumption."
        ),
        checks=dict(
            independentPeerBuckets="pass",
            sixLegitimateLoginsOnePeer="pass",
            twoLegitimateClientsConcurrent="pass",
            spoofHeaders="pass",
            staticRequestsUnthrottled="pass",
            globalBudget="pass",
            concurrency="pass",
            nginxRestartResets="pass",
            springRestartResets="pass",
        ),
        unauthorizedNetwork=denied,
        legitimateSuccesses=len(legitimate) + len(legitimate_parallel),
        legitimateFalseThrottles=0,
        expectedIdentityContentionThrottles=sum(
            r["status"] == 429 for r in same_identity
        ),
        expectedHashOverloadThrottles=sum(r["status"] == 429 for r in concurrent),
        workloadObservations={
            name: dict(
                count=len(group),
                successfulLogins=sum(r["validToken"] for r in group),
                statuses=dict(Counter(str(r["status"]) for r in group)),
                medianMs=statistics.median(r["ms"] for r in group),
                p95Ms=None,  # Fewer than20 observations; retain individual samples.
            )
            for name, group in [
                ("sequentialLegitimate", legitimate),
                ("concurrentLegitimateWithinCap", legitimate_parallel),
                ("sameIdentityContention", same_identity),
                ("distinctIdentityHashOverload", concurrent),
                ("wrongAndUnknown", wrong),
            ]
        },
        successfulLoginsAcrossAllScenarios=sum(r["validToken"] for r in samples),
        statusDistribution=dict(Counter(str(r["status"]) for r in samples)),
        rejectedByLayer=dict(
            Counter(r["layer"] for r in samples if r["status"] == 429)
        ),
        peakHashConcurrency=counters["LOGIN_HASH_PEAK"],
        counters=counters,
        latencyMs=dict(
            samples=len(durations),
            median=statistics.median(durations),
            p95=durations[math.ceil(0.95 * len(durations)) - 1],
        ),
        samples=samples,
    )
