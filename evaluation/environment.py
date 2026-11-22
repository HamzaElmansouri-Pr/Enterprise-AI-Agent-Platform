"""Own one fresh Compose project. No caller-selected target, DB or project name."""

import json
import os
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = "gpt-5.4-mini-2026-03-17"
EMBEDDING = "intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Evaluation API redirects forbidden")


class Lab:
    def __init__(self, model_dir, hosted=False):
        self.hosted = hosted
        self.model_dir = Path(model_dir).resolve(strict=True)
        if self.model_dir.is_relative_to(ROOT):
            raise ValueError("Model must be prepared outside the repository")
        self.project = "eaap-eval-" + uuid.uuid4().hex
        self.temp = tempfile.TemporaryDirectory(prefix=self.project + "-")
        self.directory = Path(self.temp.name)
        self.env = os.environ.copy()
        for key in list(self.env):
            if key.startswith("COMPOSE_") or key in {
                "OPENAI_API_KEY",
                "EXECUTION_MODEL",
            }:
                del self.env[key]
        self.values = {
            k: secrets.token_urlsafe(32)
            for k in (
                "POSTGRES_PASSWORD",
                "MIGRATION_DB_PASSWORD",
                "DB_PASSWORD",
                "IDENTITY_OPERATOR_DB_PASSWORD",
                "BOOTSTRAP_PASSWORD",
                "EMBEDDING_SECRET",
                "EXECUTION_INTERNAL_SECRET",
            )
        }
        self.values.update(
            POSTGRES_USER="eaap_dev",
            POSTGRES_DB="eaap_dev",
            DB_HOST="postgres",
            DB_PORT="5432",
            BOOTSTRAP_TENANT_ID=str(uuid.uuid4()),
            BOOTSTRAP_USER_ID=str(uuid.uuid4()),
            BOOTSTRAP_TENANT_NAME="Synthetic evaluation",
            BOOTSTRAP_EMAIL="owner@example.test",
            AUTH_ISSUER="urn:eaap:synthetic-evaluation",
            AUTH_AUDIENCE="eaap-evaluation",
            AUTH_KEY_ID="evaluation-only",
            EMBEDDING_MODEL_DIR=str(self.model_dir),
            AUTH_KEYS_DIR=str(self.directory / "keys"),
            OPENAI_API_KEY=os.environ.get("OPENAI_API_KEY", "") if hosted else "",
            EXECUTION_MODEL=MODEL if hosted else "",
        )
        self.env.update(self.values)
        self.tokens = []
        self.tenant = self.values["BOOTSTRAP_TENANT_ID"]
        self.prefix = "/tenants/" + self.tenant
        self.command = [
            "docker",
            "compose",
            "--env-file",
            str(self.directory / "local.env"),
            "-p",
            self.project,
            "-f",
            str(ROOT / "compose.yaml"),
            "-f",
            str(ROOT / "compose.auth.yaml"),
            "-f",
            str(ROOT / "compose.knowledge.yaml"),
            "-f",
            str(ROOT / "compose.execution.yaml"),
            "-f",
            str(self.directory / "isolation.yaml"),
        ]
        self.started = False
        self.phase = "initialization"
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect()
        )

    def compose(self, args):
        self.phase = "compose-" + args[0]
        result = subprocess.run(
            self.command + args,
            check=False,
            cwd=ROOT,
            env=self.env,
            capture_output=True,
            timeout=900,
        )
        if result.returncode:
            # Compose errors can print expanded secrets. Never propagate their bodies.
            raise RuntimeError("Disposable Compose phase failed: " + args[0])
        return result.stdout

    def start(self):
        keys = self.directory / "keys"
        keys.mkdir(mode=0o700)
        for args in (
            [
                "genpkey",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:3072",
                "-out",
                str(keys / "private.pem"),
            ],
            [
                "pkey",
                "-in",
                str(keys / "private.pem"),
                "-pubout",
                "-out",
                str(keys / "public.pem"),
            ],
        ):
            subprocess.run(["openssl", *args], check=True, capture_output=True)
        subprocess.run(
            [
                "docker",
                "run",
                "--rm",
                "--network",
                "none",
                "--user",
                "0",
                "-v",
                str(keys) + ":/keys",
                "eclipse-temurin:21.0.13.1_2-jre-jammy",
                "sh",
                "-c",
                "chgrp -R 10001 /keys && chmod 750 /keys && chmod 640 /keys/*.pem",
            ],
            check=True,
            capture_output=True,
        )
        secretfile = self.directory / "local.env"
        secretfile.touch(mode=0o600)
        secretfile.write_text(
            "".join(k + "=" + v + "\n" for k, v in self.values.items())
        )
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        self.url = f"http://127.0.0.1:{port}/api/v1"
        isolation = {
            "services": {
                "control-plane": {
                    "image": self.project + "-java:local",
                    "ports": [f"127.0.0.1:{port}:8080"],
                    "networks": ["default", "api-access"],
                },
                "agent-runtime": {"image": self.project + "-python:local"},
                "web": {"image": self.project + "-web:local", "ports": []},
            },
            "networks": {
                "default": {"internal": not self.hosted},
                "api-access": {},
            },
        }
        # Compose YAML tags reset inherited ports; everything else is JSON (valid YAML).
        text = json.dumps(isolation, indent=2).replace(
            '"ports": [', '"ports": !override ['
        )
        (self.directory / "isolation.yaml").write_text(text)
        # Reject a collision before allowing any lifecycle mutation.
        for kind in ("container", "volume", "network"):
            command = [
                "docker",
                kind,
                "ls",
                "-q",
                "--filter",
                "label=com.docker.compose.project=" + self.project,
            ]
            if subprocess.check_output(command).strip():
                raise RuntimeError("Refusing an existing evaluation project")
        self.started = True
        self.compose(["config", "--quiet"])
        self.compose(["build", "control-plane", "agent-runtime", "web"])
        self.compose(["up", "-d", "--wait", "postgres"])
        self.compose(
            [
                "exec",
                "-T",
                "postgres",
                "sh",
                "-c",
                'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d '
                '"$POSTGRES_DB" -f /opt/eaap/knowledge-extension.sql',
            ]
        )
        self.compose(
            [
                "run",
                "--rm",
                "--no-deps",
                "-e",
                "MIGRATION_DB_PASSWORD",
                "control-plane",
                "--spring.profiles.active=migration",
            ]
        )
        boot = ["run", "--rm", "--no-deps", "-e", "IDENTITY_OPERATOR_DB_PASSWORD"]
        for key in self.values:
            if key.startswith("BOOTSTRAP_"):
                boot += ["-e", key]
        self.compose(
            boot + ["control-plane", "--spring.profiles.active=identity-bootstrap"]
        )
        self.compose(["up", "-d", "--wait"])
        self.token = self.login(
            self.values["BOOTSTRAP_EMAIL"], self.values["BOOTSTRAP_PASSWORD"]
        )
        self.agent = self.api(
            "POST",
            self.prefix + "/agents",
            {
                "name": "Synthetic benchmark",
                "systemInstructions": "Answer using current authorized evidence only.",
            },
            expected=201,
        )["id"]
        self.agent_path = self.prefix + "/agents/" + self.agent
        self.member = self.invite("member@example.test", "MEMBER")
        self.manager = self.invite("manager@example.test", "ADMIN")

    def request(
        self, method, path, body=None, token=None, content_type="application/json"
    ):
        self.phase = "api-" + method
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("Invalid relative API path")
        headers = {"Content-Type": content_type}
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request(
            self.url + path, data=body, headers=headers, method=method
        )
        start = time.monotonic()
        try:
            response = self.opener.open(req, timeout=65)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(262145)
            if len(raw) > 262144:
                raise ValueError("API response limit")
            data = json.loads(raw) if raw else None
            return response.code, data, (time.monotonic() - start) * 1000

    def api(self, method, path, body=None, token=None, expected=200):
        status, data, _ = self.request(
            method, path, body, self.token if token is None else token
        )
        if status != expected:
            raise RuntimeError(
                f"Unexpected API HTTP status {status}; expected {expected}"
            )
        return data

    def login(self, email, password):
        status, data, _ = self.request(
            "POST", "/auth/login", {"email": email, "password": password}
        )
        if status != 200:
            raise RuntimeError("Synthetic login failed")
        self.tokens.append(data["accessToken"])
        return data["accessToken"]

    def invite(self, email, role):
        password = secrets.token_urlsafe(32)
        invite = self.api(
            "POST",
            self.prefix + "/invitations",
            {"email": email, "role": role},
            expected=201,
        )
        self.tokens.append(invite["token"])
        self.api(
            "POST",
            "/invitations/accept-new",
            {"email": email, "password": password, "token": invite["token"]},
            expected=201,
        )
        return self.login(email, password)

    def upload(
        self, name, content, associated=True, token=None, prefix=None, agent=None
    ):
        boundary = "evaluation-" + uuid.uuid4().hex
        fields = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="agentId"'
            f"\r\n\r\n{agent or self.agent}\r\n"
            if associated
            else ""
        )
        media = "application/pdf" if name.endswith(".pdf") else "text/plain"
        payload = (
            (
                fields
                + f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                f'filename="{name}"\r\nContent-Type: {media}\r\n\r\n'
            ).encode()
            + content
            + f"\r\n--{boundary}--\r\n".encode()
        )
        status, doc, _ = self.request(
            "POST",
            (prefix or self.prefix) + "/knowledge/documents",
            payload,
            token or self.token,
            "multipart/form-data; boundary=" + boundary,
        )
        if status != 202 or doc["status"] != "PENDING":
            raise RuntimeError("Fixture not accepted PENDING")
        for _ in range(90):
            current = self.api(
                "GET",
                (prefix or self.prefix) + "/knowledge/documents/" + doc["id"],
                token=token,
            )
            if current["status"] == "READY" and current["chunkCount"] > 0:
                if current["embeddingModel"] != EMBEDDING:
                    raise ValueError("Unexpected embedding identity")
                return current
            if current["status"] == "FAILED":
                raise RuntimeError("Fixture processing failed")
            time.sleep(2)
        raise TimeoutError("Fixture processing deadline")

    def mock(self):
        if self.hosted:
            raise ValueError("Cannot replace hosted provider")
        fixture = ROOT / "evaluation" / "mock_runtime.py"
        overlay = self.directory / "mock.yaml"
        overlay.write_text(
            json.dumps(
                {
                    "services": {
                        "agent-runtime": {
                            "environment": {
                                "OPENAI_API_KEY": "synthetic-not-a-provider-key",
                                "EXECUTION_MODEL": MODEL,
                                "PYTHONPATH": "/evaluation:/app",
                            },
                            "volumes": [
                                str(fixture) + ":/evaluation/mock_runtime.py:ro"
                            ],
                            "command": [
                                "uvicorn",
                                "mock_runtime:app",
                                "--host",
                                "0.0.0.0",
                                "--port",
                                "8000",
                                "--limit-concurrency",
                                "8",
                                "--backlog",
                                "16",
                            ],
                        }
                    }
                }
            )
        )
        self.command += ["-f", str(overlay)]
        self.compose(["up", "-d", "--wait", "agent-runtime"])

    def proof(self):
        return json.loads(
            self.compose(
                ["exec", "-T", "agent-runtime", "cat", "/tmp/evaluation-proof.json"]
            )
        )

    def close(self):
        try:
            if self.started:
                logs = self.compose(["logs", "--no-color"]).decode(errors="replace")
                forbidden = [
                    v
                    for k, v in self.values.items()
                    if (
                        k.endswith("PASSWORD")
                        or k.endswith("SECRET")
                        or k == "OPENAI_API_KEY"
                    )
                    and v
                ] + self.tokens
                if any(value in logs for value in forbidden):
                    raise ValueError("Secret detected in disposable service logs")
        finally:
            try:
                if self.started:
                    self.compose(["down", "--volumes", "--rmi", "local"])
            finally:
                self.temp.cleanup()
                for suffix in ("java", "python", "web") if self.started else ():
                    subprocess.run(
                        [
                            "docker",
                            "image",
                            "rm",
                            self.project + "-" + suffix + ":local",
                        ],
                        check=False,
                        capture_output=True,
                    )
