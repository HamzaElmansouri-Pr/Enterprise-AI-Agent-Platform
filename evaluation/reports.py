"""Sanitized synthetic artifacts, explicit denominators, compatible-run comparison."""

import json
from collections import Counter
from pathlib import Path

from evaluation.dataset import strict_json
from evaluation.metrics import summarize


def write(report, directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    report["metrics"] = summarize(report["cases"], report.get("reviews"))
    report["caseCounts"] = {
        "category": dict(
            Counter(c.get("category", "unknown") for c in report["cases"])
        ),
        "language": dict(Counter(c["language"] for c in report["cases"])),
    }
    raw = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    target = directory / "results.json"
    temporary = directory / ".results.tmp"
    temporary.write_text(raw)
    temporary.replace(target)
    lines = [
        f"# Synthetic evaluation {report['experimentId']}",
        "",
        f"Status: {report['status']}; hosted inference: "
        f"{report['hostedInferenceOccurred']}",
        f"Date: {report['startedAt']}; commit: `{report['commit']}`; "
        f"dirty: {report['dirty']}",
        f"Dataset: {report['dataset']['version']} / `{report['dataset']['hash']}`",
        f"Source fingerprint: `{report['sourceHash']}`",
        "",
        "## Configuration",
        "",
        "```json",
        json.dumps(report.get("configuration", {}), indent=2),
        "```",
        "",
        "Real retrieval and deterministic boundaries are separate "
        "from provider quality. Mock transport is contract "
        "evidence only. Human correctness and semantic citation "
        "support are unmeasured until reviewed.",
        "",
        "## Counts",
        "",
        "```json",
        json.dumps(report["metrics"]["counts"], indent=2),
        json.dumps(report["caseCounts"], indent=2),
        "```",
        "",
        "## Metrics (explicit numerators and denominators)",
        "",
        "```json",
        json.dumps(report["metrics"], indent=2),
        "```",
        "",
        "## Failures and representative outcomes",
        "",
    ]
    misses = [
        c
        for c in report["cases"]
        if c.get("relevant")
        and not set(c["relevant"]).intersection(c.get("ranked", [])[:1])
    ]
    selected = (
        [c for c in report["cases"] if c["status"] != "ok"]
        + misses[:3]
        + [c for c in report["cases"] if c["status"] == "ok"][:3]
    )
    for case in selected:
        # Case IDs originate in validated synthetic fixtures; use JSON code formatting.
        lines.append(
            "- "
            + json.dumps(
                {
                    k: case[k]
                    for k in (
                        "caseId",
                        "status",
                        "evidence",
                        "errorCategory",
                        "detail",
                        "relevant",
                        "ranked",
                    )
                    if k in case
                },
                ensure_ascii=False,
            )
        )
    lines += [
        "",
        "## Limits",
        "",
        "Small synthetic convenience sample; language estimates "
        "are unstable. No statistical significance is inferred. "
        "Scores are similarities, not probabilities. Latency "
        "includes HTTP overhead and is not a load benchmark. p95 "
        "uses nearest rank and is omitted below 20 samples. "
        "Unknown usage stays unknown. Billing totals are "
        "unavailable. Dataset labels and Darija phrasing need "
        "independent review. No paid inference occurs in offline "
        "mode.",
        "",
        "Configuration and individual measured outcomes are in results.json.",
    ]
    (directory / "report.md").write_text("\n".join(lines) + "\n")


def review_template(report):
    return {
        "schemaVersion": 1,
        "experimentId": report["experimentId"],
        "datasetHash": report["dataset"]["hash"],
        "reviewer": "",
        "rubric": "Correctness and groundedness separately: 0 "
        "incorrect/unsupported; 1 partial; 2 correct/supported. "
        "Cite claim evidence and explain abstention. Null is "
        "unreviewed.",
        "cases": [
            {
                "caseId": c["caseId"],
                "correctness": None,
                "groundedness": None,
                "abstained": None,
                "supportedClaims": None,
                "unsupportedClaims": None,
                "incompleteCitations": None,
                "injectionSucceeded": None,
                "rationale": "",
            }
            for c in report["cases"]
            if c["evidence"] == "hosted"
        ],
    }


def apply_review(report, form):
    if (
        form.get("schemaVersion") != 1
        or form.get("experimentId") != report["experimentId"]
        or form.get("datasetHash") != report["dataset"]["hash"]
        or not isinstance(form.get("reviewer"), str)
        or not form["reviewer"].strip()
    ):
        raise ValueError("Review identity/provenance missing")
    allowed = {c["caseId"]: c for c in report["cases"] if c["evidence"] == "hosted"}
    reviewed = []
    seen = set()
    for entry in form["cases"]:
        if (
            set(entry)
            != {
                "caseId",
                "correctness",
                "groundedness",
                "abstained",
                "supportedClaims",
                "unsupportedClaims",
                "incompleteCitations",
                "injectionSucceeded",
                "rationale",
            }
            or entry["caseId"] in seen
            or entry["caseId"] not in allowed
        ):
            raise ValueError("Unknown/duplicate review case")
        seen.add(entry["caseId"])
        if entry["correctness"] is None:
            if any(
                entry[k] is not None
                for k in (
                    "groundedness",
                    "abstained",
                    "supportedClaims",
                    "unsupportedClaims",
                    "incompleteCitations",
                    "injectionSucceeded",
                )
            ):
                raise ValueError("Partial review entry")
            continue
        case = allowed[entry["caseId"]]
        run = case.get("run", {})
        reviewable = run.get("status") == "SUCCEEDED" or (
            run.get("failureCode") == "INSUFFICIENT_KNOWLEDGE"
            and run.get("usage", {}).get("inputTokens") is not None
        )
        if not reviewable:
            raise ValueError(
                "Transport/provider failure is not a quality judgment or abstention"
            )
        for key in ("correctness", "groundedness"):
            if type(entry[key]) is not int or entry[key] not in (0, 1, 2):
                raise ValueError("Invalid rubric score")
        for key in ("abstained", "incompleteCitations"):
            if type(entry[key]) is not bool:
                raise ValueError("Missing review judgment")
        if (
            entry["injectionSucceeded"] is not None
            and type(entry["injectionSucceeded"]) is not bool
        ):
            raise ValueError("Invalid attack judgment")
        if (
            not isinstance(entry["rationale"], str)
            or not entry["rationale"].strip()
            or len(entry["rationale"]) > 4000
        ):
            raise ValueError("Review rationale required")
        for key in ("supportedClaims", "unsupportedClaims"):
            if type(entry[key]) is not int or not 0 <= entry[key] <= 100:
                raise ValueError("Invalid claim count")
        reviewed.append(
            {
                **entry,
                "language": case["language"],
                "expectedBehavior": case["expectedBehavior"],
            }
        )
    report["reviews"] = reviewed
    report["reviewer"] = form["reviewer"]
    return report


def compare(baseline, candidate):
    compatible = (
        baseline["dataset"] == candidate["dataset"]
        and baseline["configuration"] == candidate["configuration"]
    )
    metrics = []

    def walk(a, b, path=""):
        if isinstance(a, dict) and isinstance(b, dict):
            if "value" in a and "value" in b:
                av, bv = a["value"], b["value"]
                metrics.append(
                    {
                        "metric": path,
                        "baseline": av,
                        "candidate": bv,
                        "absoluteChange": bv - av
                        if av is not None and bv is not None
                        else None,
                        "relativeChange": (bv - av) / abs(av)
                        if av not in (None, 0) and bv is not None
                        else None,
                        "baselineCount": a["denominator"],
                        "candidateCount": b["denominator"],
                    }
                )
            else:
                for key in sorted(a.keys() & b.keys()):
                    walk(a[key], b[key], path + "/" + key)

    walk(baseline["metrics"], candidate["metrics"])
    security_regression = any(
        c["status"] != "ok"
        for c in candidate["cases"]
        if c["evidence"] in {"boundary", "mock-contract"}
        or c.get("category") in {"memory", "citation", "authorization"}
    )
    return {
        "compatible": compatible,
        "warning": None
        if compatible
        else "Different dataset, labels or evaluation configuration; "
        "changes cannot be attributed to application quality.",
        "securityRegression": security_regression,
        "metrics": metrics,
        "retrievalGate": "Observation only until independently reviewed baseline is "
        "accepted. No invented quality threshold.",
    }


def read(path):
    return strict_json(Path(path).read_bytes())
