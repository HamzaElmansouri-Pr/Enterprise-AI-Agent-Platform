"""CI Nginx boundary check with disposable TLS and a test-only stdlib upstream.

This verifies proxy mechanics, not Spring authentication. The full pilot drill
runs the same TCP-source approach against real synthetic identities and Spring.
"""

import json
import re
import tempfile
import time
import uuid
from pathlib import Path
from types import SimpleNamespace

from deploy.scripts.identity_ingress import CLIENT, helper
from deploy.scripts.pilot import ROOT, checked, local_docker

UPSTREAM = r"""
from http.server import BaseHTTPRequestHandler,HTTPServer
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length",0)))
        names=["Forwarded","X-Forwarded-For","X-Real-IP",
               "X-Forwarded-Host","X-Forwarded-Proto"]
        unsafe=any(self.headers.get(n) for n in names)
        self.send_response(500 if unsafe else 401)
        self.send_header("Content-Type","application/json");self.end_headers()
        self.wfile.write(b'{"code":"invalid_credentials"}')
HTTPServer(("0.0.0.0",8080),Handler).serve_forever()
"""


def main():
    local_docker()
    name = "eaap-ingress-test-" + uuid.uuid4().hex
    nginx = re.search(
        r"FROM (nginx:[^\s]+)", (ROOT / "deploy/pilot/Dockerfile.web").read_text()
    )[1]
    python = re.search(
        r"FROM (python:[^\s]+)",
        (ROOT / "services/agent-runtime/Dockerfile").read_text(),
    )[1]
    pilot = SimpleNamespace(images={"agent-runtime": python})
    owned = []
    try:
        checked(["docker", "network", "create", "--internal", name])
        owned.append(("network", name))
        with tempfile.TemporaryDirectory(prefix="eaap-ingress-tls-") as directory:
            directory = Path(directory)
            directory.chmod(0o755)
            key, cert = directory / "tls.key", directory / "tls.crt"
            checked(
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
                    "/CN=127.0.0.1",
                    "-addext",
                    "subjectAltName=IP:127.0.0.1",
                    "-keyout",
                    str(key),
                    "-out",
                    str(cert),
                ]
            )
            # One-day synthetic TLS material in this temporary owned directory.
            key.chmod(0o644)
            cert.chmod(0o644)
            for suffix, image in [("-upstream", python), ("-web", nginx)]:
                container = name + suffix
                common = [
                    "docker",
                    "run",
                    "-d",
                    "--name",
                    container,
                    "--network",
                    name,
                    "--user",
                    "10001:10001",
                    "--read-only",
                    "--cap-drop",
                    "ALL",
                    "--security-opt",
                    "no-new-privileges:true",
                    "--memory",
                    "128m",
                ]
                if suffix == "-upstream":
                    common += [
                        "--network-alias",
                        "control-plane",
                        "--entrypoint",
                        "python",
                        image,
                        "-c",
                        UPSTREAM,
                    ]
                else:
                    common += [
                        "--tmpfs",
                        "/tmp:uid=10001,gid=10001,mode=0700,size=32m",
                        "-v",
                        str(ROOT / "deploy/pilot/nginx.conf")
                        + ":/etc/nginx/nginx.conf:ro",
                        "-v",
                        str(directory) + ":/run/secrets:ro",
                        image,
                        "nginx",
                        "-g",
                        "daemon off;",
                    ]
                checked(common)
                owned.append(("container", container))
            payload = dict(ca=cert.read_text(), requests=[])
            headers = {
                "Content-Type": "application/json",
                "X-Forwarded-For": "203.0.113.1",
                "X-Real-IP": "203.0.113.2",
                "Forwarded": "for=203.0.113.3",
                "X-Forwarded-Host": "forged.invalid",
                "X-Forwarded-Proto": "http",
                "X-Client-Identity": "ignored",
            }

            def request(source, method="POST", path="/api/v1/auth/login"):
                return [source, "{}", headers, method, path]

            # Bounded readiness loop; startup failures retain a nonzero test result.
            for _ in range(20):
                try:
                    payload["requests"] = [request("127.0.0.4", "GET", "/")]
                    result = helper(
                        pilot, "container:" + name + "-web", CLIENT, payload
                    )
                    if result[0]["status"] == 200:
                        break
                except Exception:
                    time.sleep(0.1)
            else:
                raise AssertionError("Disposable Nginx did not start")
            payload["requests"] = [request("127.0.0.2") for _ in range(11)]
            first = helper(pilot, "container:" + name + "-web", CLIENT, payload)
            assert [r["status"] for r in first] == [401] * 10 + [429]
            assert first[-1]["layer"] == "ingress" and first[-1]["retryAfter"] == "6"
            payload["requests"] = [
                request("127.0.0.3"),
                request("127.0.0.2", "GET", "/"),
            ]
            second = helper(pilot, "container:" + name + "-web", CLIENT, payload)
            assert [r["status"] for r in second] == [401, 200]
            print(
                json.dumps(
                    dict(
                        nginxTcpPeerIsolation="pass",
                        forwardedHeadersStripped="pass",
                        staticUnthrottled="pass",
                        retryAfter="pass",
                    )
                )
            )
    finally:
        for kind, identifier in reversed(owned):
            checked(
                ["docker", kind, "rm"]
                + (["-f"] if kind == "container" else [])
                + [identifier]
            )


if __name__ == "__main__":
    main()
