"""Synthetic TLS client executed in a disposable helper network namespace."""

import concurrent.futures
import http.client
import json
import ssl
import sys
import threading
import time

p = json.load(sys.stdin)
ctx = ssl.create_default_context(cadata=p["ca"])
barrier = threading.Barrier(len(p["requests"])) if p.get("parallel") else None


def request(item):
    source, body, headers, method, path = item
    started = time.monotonic()
    c = http.client.HTTPSConnection(
        "127.0.0.1", 8443, context=ctx, timeout=15, source_address=(source, 0)
    )
    c.connect()
    actual = c.sock.getsockname()[0]
    if barrier is not None:
        barrier.wait(timeout=10)
    c.request(method, path, body, headers)
    r = c.getresponse()
    raw = r.read(8193)
    status = r.status
    layer = r.getheader("X-Login-Limit", "")
    retry = r.getheader("Retry-After", "")
    c.close()
    valid = False
    if status == 200 and path == "/api/v1/auth/login":
        value = json.loads(raw)
        valid = (
            value.get("tokenType") == "Bearer"
            and value.get("expiresIn") == 900
            and bool(value.get("accessToken"))
        )
    if status == 429:
        assert json.loads(raw) == {"code": "too_many_attempts"}
    if status == 401:
        assert json.loads(raw) == {"code": "invalid_credentials"}
    return dict(
        source=actual,
        status=status,
        layer=layer,
        retryAfter=retry,
        validToken=valid,
        ms=(time.monotonic() - started) * 1000,
    )


if p.get("parallel"):
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        result = list(pool.map(request, p["requests"]))
else:
    result = [request(r) for r in p["requests"]]
print(json.dumps(result))
