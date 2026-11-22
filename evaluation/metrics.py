"""Document-level macro metrics. Failed labeled queries contribute zero."""

import math
import statistics


def fraction(numerator, denominator):
    return {
        "value": numerator / denominator if denominator else None,
        "numerator": numerator,
        "denominator": denominator,
    }


def unique_documents(results):
    return list(dict.fromkeys(row["documentId"] for row in results))


def retrieval(rows):
    labeled = [r for r in rows if r.get("relevant")]
    result = {}
    for k in (1, 3, 5):
        recall = hit = precision = 0
        for row in labeled:
            ranked = (
                row.get("ranked", [])[:k]
                if row.get("retrievalStatus", row["status"]) == "ok"
                else []
            )
            relevant = set(row["relevant"])
            found = len(relevant.intersection(ranked))
            recall += found / len(relevant)
            hit += int(found > 0)
            precision += found / k
        for name, total in (
            ("recall", recall),
            ("hitRate", hit),
            ("precision", precision),
        ):
            result[f"{name}@{k}"] = fraction(total, len(labeled))
    rr = 0
    for row in labeled:
        if row.get("retrievalStatus", row["status"]) == "ok":
            rr += next(
                (
                    1 / i
                    for i, doc in enumerate(row.get("ranked", []), 1)
                    if doc in row["relevant"]
                ),
                0,
            )
    result["mrr"] = fraction(rr, len(labeled))
    return result


def latency(values):
    if not values:
        return {"count": 0, "medianMs": None, "p95Ms": None}
    ordered = sorted(values)
    return {
        "count": len(values),
        "medianMs": statistics.median(values),
        "p95Ms": ordered[math.ceil(0.95 * len(ordered)) - 1]
        if len(values) >= 20
        else None,
    }


def summarize(rows, reviews=None):
    reviews = reviews or []
    hosted = [r for r in rows if r.get("evidence") == "hosted"]
    succeeded = [r for r in hosted if r.get("run", {}).get("status") == "SUCCEEDED"]
    citations = [c for r in succeeded for c in r.get("citationChecks", [])]
    reviewed = [r for r in reviews if r["correctness"] is not None]
    unanswerable = [
        r for r in reviewed if r["expectedBehavior"] in {"abstain", "clarify"}
    ]
    answerable = [r for r in reviewed if r["expectedBehavior"] == "grounded_answer"]
    boundary = [
        r
        for r in rows
        if r.get("evidence") in {"boundary", "mock-contract"}
        or r.get("category") in {"memory", "citation", "authorization"}
    ]
    usage = [
        run["usage"]
        for r in hosted
        for run in [r.get("run", {}), *r.get("auxiliaryRuns", [])]
        if run.get("usage", {}).get("inputTokens") is not None
    ]
    return {
        "retrieval": retrieval(rows),
        "retrievalByLanguage": {
            lang: retrieval([r for r in rows if r["language"] == lang])
            for lang in sorted({r["language"] for r in rows})
        },
        "boundaryPassRate": fraction(
            sum(r["status"] == "ok" for r in boundary), len(boundary)
        ),
        "structuralCitationValidity": fraction(sum(citations), len(citations)),
        "semanticSupport": fraction(
            sum(r["supportedClaims"] for r in reviewed),
            sum(r["supportedClaims"] + r["unsupportedClaims"] for r in reviewed),
        ),
        "incompleteCitationReviewRate": fraction(
            sum(r["incompleteCitations"] for r in reviewed), len(reviewed)
        ),
        "injectionSuccessReviewRate": fraction(
            sum(r["injectionSucceeded"] is True for r in reviewed),
            sum(r["injectionSucceeded"] is not None for r in reviewed),
        ),
        "missingCitationOutputs": sum(not r.get("citationChecks") for r in succeeded),
        "reviewCorrectness": fraction(
            sum(r["correctness"] for r in reviewed), 2 * len(reviewed)
        ),
        "reviewCoverage": fraction(len(reviewed), len(hosted)),
        "reviewGroundedness": fraction(
            sum(r["groundedness"] for r in reviewed), 2 * len(reviewed)
        ),
        "abstentionRate": fraction(
            sum(r["abstained"] for r in reviewed), len(reviewed)
        ),
        "correctAbstentionRate": fraction(
            sum(r["abstained"] for r in unanswerable), len(unanswerable)
        ),
        "falseAbstentionRate": fraction(
            sum(r["abstained"] for r in answerable), len(answerable)
        ),
        "reviewByLanguage": {
            lang: {
                "count": len(group := [r for r in reviewed if r["language"] == lang]),
                "correctness": fraction(
                    sum(r["correctness"] for r in group), 2 * len(group)
                ),
                "groundedness": fraction(
                    sum(r["groundedness"] for r in group), 2 * len(group)
                ),
            }
            for lang in sorted({r["language"] for r in reviewed})
        },
        "latency": latency([r["latencyMs"] for r in rows if "latencyMs" in r]),
        "knownUsage": {
            "runs": len(usage),
            "inputTokens": sum(u["inputTokens"] for u in usage),
            "outputTokens": sum(u["outputTokens"] for u in usage),
        },
        "errors": {
            kind: sum(r.get("errorCategory") == kind for r in rows)
            for kind in sorted(
                {r["errorCategory"] for r in rows if r.get("errorCategory")}
            )
        },
        "counts": {
            status: sum(r["status"] == status for r in rows)
            for status in sorted({r["status"] for r in rows})
        },
    }
