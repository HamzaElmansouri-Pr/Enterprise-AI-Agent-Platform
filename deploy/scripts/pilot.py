"""Local operator CLI. All commands use an explicit pilot config, never eaap-dev."""

import argparse
import hashlib
import ipaddress
import json
import os
import re
import secrets
import stat
import subprocess
import tempfile
import uuid
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
SECRET_NAMES = [
    "POSTGRES_PASSWORD",
    "DB_PASSWORD",
    "MIGRATION_DB_PASSWORD",
    "IDENTITY_OPERATOR_DB_PASSWORD",
    "EMBEDDING_SECRET",
    "EXECUTION_INTERNAL_SECRET",
    "OPERATIONS_SECRET",
]
FILES = SECRET_NAMES + ["jwt-private.pem", "jwt-public.pem", "tls.crt", "tls.key"]
PG_IMAGE = (
    "pgvector/pgvector:0.8.7-pg17-trixie@sha256:89947206f52eac3"
    "a9df9b663df7e76987cbdc623be6576b3a524953d568700d7"
)


class OperationError(RuntimeError):
    """Only fixed safe messages may cross the CLI error boundary."""


def checked(command, *, data=None, timeout=900, env=None):
    try:
        result = subprocess.run(
            command,
            input=data,
            capture_output=True,
            timeout=timeout,
            env=env,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise OperationError("Operator command unavailable or timed out") from None
    if result.returncode:
        raise OperationError(
            "Operator command failed; private diagnostic bodies omitted"
        )
    return result.stdout


def local_docker():
    # Inspect configuration locally before any connection/mutation. No remote contexts.
    context = os.environ.get("DOCKER_CONTEXT")
    endpoint = os.environ.get("DOCKER_HOST") if not context else None
    if not endpoint:
        command = (
            ["docker", "context", "inspect"]
            + ([context] if context else [])
            + ["--format", "{{.Endpoints.docker.Host}}"]
        )
        endpoint = checked(command).decode().strip()
    if not endpoint.startswith("unix:///"):
        raise OperationError("Pilot tooling requires a local Unix Docker endpoint")


def private_file(path, maximum=65536):
    path = Path(path)
    info = path.lstat()
    if (
        not stat.S_ISREG(info.st_mode)
        or info.st_mode & 0o027
        or not 0 < info.st_size <= maximum
    ):
        raise OperationError(
            "Secret file must be regular, bounded, and owner/group protected"
        )
    return path.read_bytes()


def load_config(path):
    path = Path(path).resolve(strict=True)
    config = json.loads(path.read_bytes())
    expected = {
        "version",
        "mode",
        "project",
        "release",
        "secretDir",
        "modelDir",
        "bind",
        "port",
        "issuer",
        "audience",
        "keyId",
        "providerMode",
        "imageLock",
        "localTest",
    }
    if (
        set(config) != expected
        or type(config["version"]) is not int
        or config["version"] != 1
        or config["mode"] != "pilot"
    ):
        raise OperationError("Unsupported pilot configuration")
    if not re.fullmatch(r"eaap-pilot-[a-z0-9-]{8,64}", config["project"]):
        raise OperationError("Explicit isolated pilot project required")
    if not re.fullmatch(r"[a-f0-9]{40}", config["release"]):
        raise OperationError("Exact release commit required")
    if (
        type(config["port"]) is not int
        or not 1024 <= config["port"] <= 65535
        or config["bind"] not in {"127.0.0.1", "0.0.0.0"}
    ):
        raise OperationError("Unsupported HTTPS ingress binding")
    if type(config["localTest"]) is not bool or (
        config["localTest"] and config["bind"] != "127.0.0.1"
    ):
        raise OperationError("Test certificates require loopback-only binding")
    if (
        not isinstance(config["issuer"], str)
        or not config["issuer"].startswith("https://")
        or not config["audience"]
        or not config["keyId"]
    ):
        raise OperationError("Explicit HTTPS identity configuration required")
    issuer = urlsplit(config["issuer"])
    if (
        not issuer.hostname
        or issuer.username
        or issuer.password
        or issuer.query
        or issuer.fragment
    ):
        raise OperationError("Invalid HTTPS issuer")
    if config["providerMode"] not in {"disabled", "hosted"}:
        raise OperationError("Explicit provider mode required")
    directory = Path(config["secretDir"]).resolve(strict=True)
    model = Path(config["modelDir"]).resolve(strict=True)
    if directory.is_relative_to(ROOT) or model.is_relative_to(ROOT):
        raise OperationError(
            "Secrets and model artifacts must remain outside repository/build contexts"
        )
    values = []
    for name in FILES:
        raw = private_file(directory / name)
        if name in SECRET_NAMES:
            if not re.fullmatch(rb"[!-~]{32,128}", raw):
                raise OperationError("Weak pilot credential")
            values.append(raw)
    if len(set(values)) != len(values):
        raise OperationError("Pilot credentials must be distinct")
    checked(
        [
            "openssl",
            "pkey",
            "-in",
            str(directory / "jwt-private.pem"),
            "-check",
            "-noout",
        ]
    )
    private_public = checked(
        [
            "openssl",
            "pkey",
            "-in",
            str(directory / "jwt-private.pem"),
            "-pubout",
            "-outform",
            "DER",
        ]
    )
    public = checked(
        [
            "openssl",
            "pkey",
            "-pubin",
            "-in",
            str(directory / "jwt-public.pem"),
            "-outform",
            "DER",
        ]
    )
    rsa = checked(
        [
            "openssl",
            "rsa",
            "-pubin",
            "-in",
            str(directory / "jwt-public.pem"),
            "-text",
            "-noout",
        ]
    )
    bits = re.search(rb"Public-Key: \((\d+) bit\)", rsa)
    if not bits or int(bits[1]) < 2048:
        raise OperationError("JWT RSA key must be at least 2048 bits")
    if private_public != public:
        raise OperationError("JWT key pair does not match")
    checked(
        [
            "openssl",
            "x509",
            "-in",
            str(directory / "tls.crt"),
            "-noout",
            "-checkend",
            "86400",
        ]
    )
    cert_public = checked(
        ["openssl", "x509", "-in", str(directory / "tls.crt"), "-pubkey", "-noout"]
    )
    tls_public = checked(
        ["openssl", "pkey", "-in", str(directory / "tls.key"), "-pubout"]
    )
    if cert_public != tls_public:
        raise OperationError("TLS key pair does not match")
    try:
        ipaddress.ip_address(issuer.hostname)
        hostname_option = "-checkip"
    except ValueError:
        hostname_option = "-checkhost"
    checked(
        [
            "openssl",
            "x509",
            "-in",
            str(directory / "tls.crt"),
            "-noout",
            hostname_option,
            issuer.hostname,
        ]
    )
    if not config["localTest"]:
        # Trust/certificate hostname is independently validated before internet use.
        checked(
            ["openssl", "verify", "-purpose", "sslserver", str(directory / "tls.crt")]
        )
    manifest = json.loads((model / "manifest.json").read_bytes())
    if (
        manifest["modelId"]
        != "intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3"
    ):
        raise OperationError("Unsupported embedding model")
    for filename in (
        "model.onnx",
        "config.json",
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
    ):
        with (model / filename).open("rb") as stream:
            if (
                hashlib.file_digest(stream, "sha256").hexdigest()
                != manifest["sha256"][filename]
            ):
                raise OperationError("Embedding artifact integrity failed")
    if config["providerMode"] == "hosted":
        key = private_file(directory / "OPENAI_API_KEY")
        if (
            len(key) < 32
            or not key.startswith(b"sk-")
            or any(
                s in key.lower()
                for s in (b"placeholder", b"example", b"test-only", b"changeme")
            )
        ):
            raise OperationError(
                "Hosted configuration requires a nonplaceholder provider key"
            )
    return config


class Pilot:
    def __init__(self, path, require_images=True):
        local_docker()
        self.path = Path(path).resolve()
        self.config = load_config(path)
        self.project = self.config["project"]
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("COMPOSE_")}
        self.env.update(
            PILOT_SECRET_DIR=self.config["secretDir"],
            PILOT_MODEL_DIR=self.config["modelDir"],
            PILOT_BIND=self.config["bind"],
            PILOT_PORT=str(self.config["port"]),
            PILOT_RELEASE_SHA=self.config["release"],
            PILOT_ISSUER=self.config["issuer"],
            PILOT_AUDIENCE=self.config["audience"],
            PILOT_KEY_ID=self.config["keyId"],
        )
        self.images = (
            json.loads(Path(self.config["imageLock"]).read_bytes())
            if require_images
            else {}
        )
        if require_images:
            if set(self.images) != {"web", "control-plane", "agent-runtime"} or any(
                not re.fullmatch(r"sha256:[a-f0-9]{64}", i)
                for i in self.images.values()
            ):
                raise OperationError("Immutable image lock required")
            for image in self.images.values():
                revision = (
                    checked(
                        [
                            "docker",
                            "image",
                            "inspect",
                            image,
                            "--format",
                            "{{index .Config.Labels "
                            '"org.opencontainers.image.revision"}}',
                        ]
                    )
                    .decode()
                    .strip()
                )
                if revision != self.config["release"]:
                    raise OperationError(
                        "Image release does not match pilot configuration"
                    )
        for service, name in (
            ("web", "PILOT_WEB_IMAGE"),
            ("control-plane", "PILOT_CONTROL_PLANE_IMAGE"),
            ("agent-runtime", "PILOT_RUNTIME_IMAGE"),
        ):
            self.env[name] = self.images.get(service, "unbuilt:preflight-only")
        # Never load the developer .env.
        self.command = [
            "docker",
            "compose",
            "--env-file",
            "/dev/null",
            "-p",
            self.project,
            "-f",
            str(ROOT / "deploy/pilot/compose.yaml"),
        ]
        if self.config["providerMode"] == "hosted":
            self.command += ["-f", str(ROOT / "deploy/pilot/compose.hosted.yaml")]

    def compose(self, args, data=None, timeout=900):
        return checked(self.command + args, data=data, env=self.env, timeout=timeout)

    def preflight(self):
        plan = json.loads(self.compose(["config", "--format", "json"]))
        validate_plan(plan, self.config)
        return {
            "configuration": "valid",
            "project": self.project,
            "release": self.config["release"],
            "images": self.images,
        }

    def sql(self, query, user="eaap_dev", password_file=None):
        if password_file:
            # Read the file inside PostgreSQL, without a password in Docker argv/config.
            cmd = [
                "exec",
                "-T",
                "postgres",
                "sh",
                "-c",
                'export PGPASSWORD="$(cat "$1")"; exec psql -X -q -t -A -v '
                'ON_ERROR_STOP=1 -h 127.0.0.1 -U "$2" -d eaap_pilot',
                "operator",
                password_file,
                user,
            ]
        else:
            cmd = [
                "exec",
                "-T",
                "postgres",
                "psql",
                "-X",
                "-q",
                "-t",
                "-A",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                user,
                "-d",
                "eaap_pilot",
            ]
        return self.compose(cmd, data=query.encode(), timeout=60).decode().strip()

    def database_preflight(self, empty=False):
        query = (ROOT / "deploy/pilot/preflight.sql").read_text()
        data = json.loads(self.sql(query))
        if (
            data["postgresMajor"] != 17
            or data["vectorVersion"] != "0.8.7"
            or not data["rolesSafe"]
        ):
            raise OperationError(
                "Database version, extension or least-privilege check failed"
            )
        if empty:
            if data["objects"] != 0:
                raise OperationError("Restore destination is nonempty")
        elif data["migration"] != "8" or not data["forcedRls"]:
            raise OperationError("Database schema or forced RLS preflight failed")
        return data

    def operator(self, profile, values=None):
        if profile not in {"migration", "identity-bootstrap"}:
            raise OperationError("Unsupported operator profile")
        name = (
            "MIGRATION_DB_PASSWORD"
            if profile == "migration"
            else "IDENTITY_OPERATOR_DB_PASSWORD"
        )
        service = {
            "secrets": [name],
            "environment": {
                "SPRING_PROFILES_ACTIVE": profile,
                "EAAP_DEPLOYMENT_MODE": "operator",
                "SPRING_CONFIG_IMPORT": "configtree:/run/secrets/",
                **(values or {}),
            },
            "restart": "no",
        }
        extra = {}
        if profile == "identity-bootstrap":
            private_file(Path(self.config["secretDir"]) / "BOOTSTRAP_PASSWORD")
            service["secrets"].append("BOOTSTRAP_PASSWORD")
            extra["BOOTSTRAP_PASSWORD"] = {
                "file": str(Path(self.config["secretDir"]) / "BOOTSTRAP_PASSWORD")
            }
        with tempfile.TemporaryDirectory(prefix="eaap-operator-") as directory:
            overlay = Path(directory) / "operator.yaml"
            text = json.dumps(
                {"services": {"control-plane": service}, "secrets": extra}
            ).replace('"secrets": [', '"secrets": !override [')
            overlay.write_text(text)
            checked(
                self.command
                + ["-f", str(overlay), "run", "--rm", "--no-deps", "control-plane"],
                env=self.env,
            )

    def diagnostics(self):
        value = private_file(
            Path(self.config["secretDir"]) / "OPERATIONS_SECRET"
        ).decode()
        data = (
            'url = "http://127.0.0.1:9090/actuator/pilot"\nheader = '
            '"X-Operations-Secret: ' + value + '"\n'
        ).encode()
        return json.loads(
            self.compose(
                [
                    "exec",
                    "-T",
                    "control-plane",
                    "curl",
                    "--fail",
                    "--silent",
                    "--config",
                    "-",
                ],
                data=data,
            )
        )


def validate_plan(plan, config):
    services = plan["services"]
    if set(services) != {"web", "control-plane", "agent-runtime", "postgres"}:
        raise OperationError("Pilot must contain exactly the four approved services")
    expected = {
        "web": {"ingress", "api"},
        "control-plane": {"api", "runtime", "data"},
        "agent-runtime": {"runtime"}
        | ({"provider-egress"} if config["providerMode"] == "hosted" else set()),
        "postgres": {"data"},
    }
    for name, service in services.items():
        if (
            service.get("privileged")
            or service.get("network_mode")
            or service.get("cap_add")
            or service.get("pid")
            or service.get("devices")
        ):
            raise OperationError("Unsafe pilot container privileges")
        if (
            not service.get("read_only")
            or not service.get("user")
            or "ALL" not in service.get("cap_drop", [])
            or "no-new-privileges:true" not in service.get("security_opt", [])
        ):
            raise OperationError("Pilot filesystem/user/capability policy invalid")
        if (
            set(service["networks"]) != expected[name]
            or not service.get("mem_limit")
            or not service.get("healthcheck")
            or service.get("restart") != "unless-stopped"
        ):
            raise OperationError("Unsafe pilot network, resources or lifecycle")
        if name != "web" and service.get("ports"):
            raise OperationError("Internal pilot ports must never be published")
        for mount in service.get("volumes", []):
            if "docker.sock" in mount.get("source", "") or mount.get("target") in {
                "/",
                "/etc",
                "/proc",
                "/sys",
            }:
                raise OperationError("Unsafe pilot mount")
        env = service.get("environment", {})
        if any(key in env for key in SECRET_NAMES + ["OPENAI_API_KEY"]):
            raise OperationError("Pilot credentials must be file delivered")
    ports = services["web"].get("ports", [])
    if (
        len(ports) != 1
        or ports[0]["target"] != 8443
        or ports[0].get("host_ip") != config["bind"]
    ):
        raise OperationError("Only the configured HTTPS ingress may be published")
    for name in ("api", "runtime", "data"):
        if not plan["networks"][name].get("internal"):
            raise OperationError("Pilot dependency networks must be private")
    if services["postgres"]["image"] != PG_IMAGE:
        raise OperationError("Unapproved PostgreSQL/pgvector image")


def init_local(directory, model_dir, port):
    local_docker()
    directory = Path(directory).resolve()
    if directory.exists() or directory.is_relative_to(ROOT):
        raise OperationError(
            "Local-test configuration requires a new external directory"
        )
    directory.mkdir(mode=0o700, parents=True)
    secret_dir = directory / "secrets"
    secret_dir.mkdir(mode=0o700)
    for name in SECRET_NAMES + ["BOOTSTRAP_PASSWORD"]:
        path = secret_dir / name
        path.touch(mode=0o600)
        path.write_text(secrets.token_urlsafe(40))
    checked(
        [
            "openssl",
            "genpkey",
            "-algorithm",
            "RSA",
            "-pkeyopt",
            "rsa_keygen_bits:3072",
            "-out",
            str(secret_dir / "jwt-private.pem"),
        ]
    )
    checked(
        [
            "openssl",
            "pkey",
            "-in",
            str(secret_dir / "jwt-private.pem"),
            "-pubout",
            "-out",
            str(secret_dir / "jwt-public.pem"),
        ]
    )
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
            "/CN=localhost",
            "-addext",
            "subjectAltName=DNS:localhost,IP:127.0.0.1",
            "-keyout",
            str(secret_dir / "tls.key"),
            "-out",
            str(secret_dir / "tls.crt"),
        ]
    )
    checked(
        [
            "docker",
            "run",
            "--rm",
            "--network",
            "none",
            "--user",
            "0",
            "-v",
            str(secret_dir) + ":/secrets",
            "eclipse-temurin:21.0.13.1_2-jre-jammy@sha256:"
            "cdbc48132dd37b9e65cfccf2417c5668ac75046c4168af5486526eb9788f0e8f",
            "sh",
            "-c",
            "chgrp -R 10001 /secrets && chmod 750 /secrets && chmod 640 /secrets/*",
        ]
    )
    sha = checked(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).decode().strip()
    config = dict(
        version=1,
        mode="pilot",
        project="eaap-pilot-" + uuid.uuid4().hex,
        release=sha,
        secretDir=str(secret_dir),
        modelDir=str(Path(model_dir).resolve()),
        bind="127.0.0.1",
        port=port,
        issuer=f"https://localhost:{port}",
        audience="eaap-pilot",
        keyId="pilot-local-test",
        providerMode="disabled",
        imageLock=str(directory / "images.json"),
        localTest=True,
    )
    path = directory / "pilot.json"
    path.touch(mode=0o600)
    path.write_text(json.dumps(config, indent=2) + "\n")
    return path


def build(path):
    pilot = Pilot(path, require_images=False)
    pilot.preflight()
    head = checked(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).decode().strip()
    dirty = checked(["git", "-C", str(ROOT), "status", "--porcelain"]).strip()
    if head != pilot.config["release"] or (dirty and not pilot.config["localTest"]):
        raise OperationError(
            "Release build requires the configured clean source commit"
        )
    images = {}
    for service, context, dockerfile in (
        ("control-plane", ROOT / "services/control-plane", None),
        ("agent-runtime", ROOT / "services/agent-runtime", None),
        ("web", ROOT, ROOT / "deploy/pilot/Dockerfile.web"),
    ):
        tag = pilot.project + "-" + service + ":" + pilot.config["release"]
        command = [
            "docker",
            "build",
            "--build-arg",
            "RELEASE_SHA=" + pilot.config["release"],
            "-t",
            tag,
        ]
        if dockerfile:
            command += ["-f", str(dockerfile)]
        checked(command + [str(context)], timeout=1200)
        images[service] = (
            checked(["docker", "image", "inspect", tag, "--format", "{{.Id}}"])
            .decode()
            .strip()
        )
    target = Path(pilot.config["imageLock"])
    with target.open("x") as stream:
        json.dump(images, stream, indent=2)
    return images


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=[
            "init-local",
            "build",
            "preflight",
            "database-preflight",
            "start-db",
            "migrate",
            "start",
            "stop",
            "diagnostics",
            "bootstrap",
        ],
    )
    parser.add_argument("--config")
    parser.add_argument("--directory")
    parser.add_argument("--model-dir")
    parser.add_argument("--port", type=int, default=18443)
    parser.add_argument("--tenant-id")
    parser.add_argument("--user-id")
    parser.add_argument("--email")
    parser.add_argument("--tenant-name")
    args = parser.parse_args()
    try:
        if args.command == "init-local":
            if not args.directory or not args.model_dir:
                raise OperationError("Explicit directory and model required")
            result = {
                "config": str(init_local(args.directory, args.model_dir, args.port))
            }
        elif args.command == "build":
            result = build(args.config)
        else:
            pilot = Pilot(args.config)
            result = pilot.preflight()
            if args.command == "database-preflight":
                result = pilot.database_preflight()
            elif args.command == "start-db":
                pilot.compose(["up", "-d", "--wait", "postgres"])
                pilot.sql("\\i /opt/eaap/knowledge-extension.sql")
            elif args.command == "migrate":
                pilot.operator("migration")
            elif args.command == "start":
                pilot.database_preflight()
                pilot.compose(["up", "-d", "--wait"])
            elif args.command == "stop":
                pilot.compose(["stop"])
            elif args.command == "diagnostics":
                result = pilot.diagnostics()
            elif args.command == "bootstrap":
                values = {
                    "BOOTSTRAP_TENANT_ID": str(uuid.UUID(args.tenant_id)),
                    "BOOTSTRAP_USER_ID": str(uuid.UUID(args.user_id)),
                    "BOOTSTRAP_EMAIL": args.email,
                    "BOOTSTRAP_TENANT_NAME": args.tenant_name,
                }
                pilot.operator("identity-bootstrap", values)
        print(json.dumps(result))
        return 0
    except (OperationError, OSError, ValueError, KeyError, TypeError):
        print(
            "Pilot operation failed; check required files, "
            "configuration and documented preflight. Private details "
            "omitted."
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
