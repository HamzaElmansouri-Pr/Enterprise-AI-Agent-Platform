"""Owned API boundary checks plus real-graph mocked-transport history checks."""

import uuid

CHECKS = {
    "tenant-status",
    "tenant-search",
    "member-upload",
    "member-proposal",
    "creator-approve",
    "unapproved-execute",
    "unknown-tool",
    "unassociated-search",
    "valid-citation",
    "invalid-citation",
    "missing-citation",
    "citation-metadata",
    "followup-context",
    "six-turn-cap",
    "history-budget",
    "failed-excluded",
    "conversation-isolation",
    "user-isolation",
    "tenant-history",
    "closed-write",
    "replay",
    "replay-conflict",
    "archived-history",
    "history-order",
}


def run(lab, documents):
    checks = {}
    prefix, ap = lab.prefix, lab.agent_path
    unauthorized = "/tenants/10000000-0000-4000-8000-000000000099"
    first_doc = next(d["id"] for d in documents.values() if d["scope"] == "associated")

    def http(name, method, path, expected, body=None, token=None):
        status, _, _ = lab.request(method, path, body, token or lab.token)
        checks[name] = status == expected

    http(
        "tenant-status", "GET", unauthorized + "/knowledge/documents/" + first_doc, 403
    )
    http(
        "tenant-search",
        "POST",
        unauthorized + "/agents/" + lab.agent + "/knowledge/search",
        403,
        {"query": "passport", "topK": 5},
    )
    boundary = "synthetic-member-upload"
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="agentId"'
        f"\r\n\r\n{lab.agent}\r\n"
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        'filename="member.txt"\r\nContent-Type: text/plain\r\n\r\n'
        f"Synthetic member upload\r\n--{boundary}--\r\n"
    ).encode()
    code, _, _ = lab.request(
        "POST",
        prefix + "/knowledge/documents",
        body,
        lab.member,
        "multipart/form-data; boundary=" + boundary,
    )
    checks["member-upload"] = code == 403
    payload = {
        "toolName": "admissions.create_followup_task",
        "arguments": {
            "title": "Synthetic followup",
            "description": "Owned fixture only",
            "priority": "NORMAL",
        },
        "rationale": "Approval boundary check",
    }
    http("member-proposal", "POST", ap + "/tool-proposals", 403, payload, lab.member)
    proposal = lab.api("POST", ap + "/tool-proposals", payload, expected=201)
    pp = prefix + "/tool-proposals/" + proposal["id"]
    empty_before = lab.api("GET", prefix + "/admissions/followup-tasks")["items"] == []
    http(
        "creator-approve",
        "POST",
        pp + "/approve",
        403,
        {"expectedPayloadDigest": proposal["payloadDigest"]},
    )
    http("unapproved-execute", "POST", pp + "/execute", 409)
    checks["unapproved-execute"] &= (
        proposal["status"] == "PENDING_APPROVAL"
        and empty_before
        and lab.api("GET", prefix + "/admissions/followup-tasks")["items"] == []
    )
    http(
        "unknown-tool",
        "POST",
        ap + "/tool-proposals",
        400,
        {**payload, "toolName": "shell.execute"},
    )
    ranked = lab.api(
        "POST",
        ap + "/knowledge/search",
        {
            "query": "SYNTHETIC_UNASSOCIATED_CANARY SYNTHETIC_OTHER_TENANT_CANARY",
            "topK": 10,
        },
    )["results"]
    allowed = {d["id"] for d in documents.values() if d["scope"] == "associated"}
    checks["unassociated-search"] = all(
        r["documentId"] in allowed
        and "SYNTHETIC_UNASSOCIATED_CANARY" not in r["text"]
        and "SYNTHETIC_OTHER_TENANT_CANARY" not in r["text"]
        for r in ranked
    )

    lab.mock()
    cp = ap + "/conversations"
    cid = lab.api("POST", cp, {"title": "Synthetic history"}, expected=201)["id"]
    path = cp + "/" + cid

    def send(question, target=path, request_id=None, token=None):
        return lab.api(
            "POST",
            target + "/messages",
            {"message": question, "requestId": request_id or str(uuid.uuid4())},
            token=token,
        )

    request_id = str(uuid.uuid4())
    first = send("History turn 0 passport", request_id=request_id)
    checks["valid-citation"] = first["status"] == "SUCCEEDED" and bool(
        first["result"]["citations"]
    )
    checks["citation-metadata"] = all(
        c["documentId"] in allowed
        and c["filename"]
        == next(d["filename"] for d in documents.values() if d["id"] == c["documentId"])
        and c["chunkId"]
        for c in first["result"]["citations"]
    )
    checks["replay"] = (
        send("History turn 0 passport", request_id=request_id) == first
        and len(lab.proof()) == 1
    )
    http(
        "replay-conflict",
        "POST",
        path + "/messages",
        409,
        {"message": "Changed payload", "requestId": request_id},
    )
    for i in range(1, 8):
        send(f"History turn {i} passport")
    proof = lab.proof()
    checks["followup-context"] = (
        proof[1]["history"][0]["question"] == "History turn 0 passport"
    )
    checks["six-turn-cap"] = (
        len(proof[-1]["history"]) == 6
        and proof[-1]["history"][0]["question"] == "History turn 1 passport"
    )
    checks["history-order"] = [t["question"] for t in proof[-1]["history"]] == [
        f"History turn {i} passport" for i in range(1, 7)
    ]
    send("Large answer control")
    send("Large answer control")
    send("After large answers")
    checks["history-budget"] = (
        len(lab.proof()[-1]["history"]) == 1
        and sum(
            len(t["question"]) + len(t["answer"]) for t in lab.proof()[-1]["history"]
        )
        <= 8000
    )
    bad = send("Invalid citation control")
    missing = send("Missing citation control")
    checks["invalid-citation"] = (
        bad["status"] == "FAILED"
        # The accepted HTTP adapter sanitizes Python 502 details to this code.
        and bad["result"]["failureCode"] == "RUNTIME_FAILURE"
        and bad["result"]["answer"] is None
    )
    checks["missing-citation"] = (
        missing["status"] == "FAILED"
        and missing["result"]["failureCode"] == "INSUFFICIENT_KNOWLEDGE"
    )
    send("After failed turns passport")
    checks["failed-excluded"] = all(
        t["question"] not in {"Invalid citation control", "Missing citation control"}
        for t in lab.proof()[-1]["history"]
    )
    second = lab.api("POST", cp, {}, expected=201)["id"]
    send("Other conversation question", cp + "/" + second)
    checks["conversation-isolation"] = lab.proof()[-1]["history"] == []
    third = lab.api("POST", cp, {}, token=lab.member, expected=201)["id"]
    send("Other user question", cp + "/" + third, token=lab.member)
    checks["user-isolation"] = lab.proof()[-1]["history"] == []
    http("other-user-read", "GET", path + "/messages", 404, token=lab.manager)
    checks["user-isolation"] &= checks.pop("other-user-read")
    http(
        "tenant-history",
        "GET",
        unauthorized + "/agents/" + lab.agent + "/conversations/" + cid + "/messages",
        403,
    )
    lab.api("POST", path + "/close")
    http(
        "closed-write",
        "POST",
        path + "/messages",
        409,
        {"message": "After close", "requestId": str(uuid.uuid4())},
    )
    lab.api("POST", ap + "/archive")
    messages = lab.api("GET", path + "/messages?size=100")["items"]
    checks["archived-history"] = (
        len(messages) == 28 and messages[1]["content"] == "SYNTHETIC CONTRACT ANSWER"
    )
    checks["replay"] &= (
        send("History turn 0 passport", request_id=request_id) == first
        and len(lab.proof()) == 16
    )
    checks["_observations"] = {
        "invalidCitationFailure": bad["result"]["failureCode"],
        "missingCitationFailure": missing["result"]["failureCode"],
        "mockCalls": len(lab.proof()),
        "historyLengths": [len(p["history"]) for p in lab.proof()],
        "historyCharacters": [
            sum(len(t["question"]) + len(t["answer"]) for t in p["history"])
            for p in lab.proof()
        ],
    }
    return checks
