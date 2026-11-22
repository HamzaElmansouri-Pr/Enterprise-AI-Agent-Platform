"""Fast source gates: no credentials, private endpoint proxy, or frontend maps."""

import re
import subprocess

from deploy.scripts.pilot import ROOT


def main():
    paths = (
        subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
            cwd=ROOT,
            check=True,
            capture_output=True,
        )
        .stdout.decode()
        .split("\0")
    )
    for name in filter(None, paths):
        path = ROOT / name
        if path.suffix in {".key", ".pem", ".cms", ".dump"} or path.name == ".env":
            raise ValueError("Forbidden credential/archive source file: " + name)
        if path.is_file() and path.stat().st_size < 2_000_000:
            raw = path.read_bytes()
            if re.search(
                rb"(?m)^-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----", raw
            ):
                raise ValueError("Private signing material in source: " + name)
            if re.search(rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{40,}", raw):
                raise ValueError("Possible provider credential in source: " + name)
    config = (ROOT / "deploy/pilot/nginx.conf").read_text()
    assert "listen 8443 ssl" in config
    assert "proxy_pass http://control-plane:8080" in config
    assert "access_log off" in config
    assert "ssl_protocols TLSv1.2 TLSv1.3" in config
    assert 'proxy_set_header Forwarded ""' in config
    for path in (ROOT / "apps/web/dist").glob("**/*.map"):
        raise ValueError("Frontend source map in pilot build: " + path.name)
    print("Pilot source/secret/header gates passed.")


if __name__ == "__main__":
    main()
