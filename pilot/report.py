"""Assemble sanitized business evidence; screenshots still require human inspection."""

import argparse
import hashlib
import json
import math
import shutil
import statistics
import subprocess
from collections import Counter
from pathlib import Path

from pilot.dataset import SCENARIO, validate

ROOT = Path(__file__).resolve().parents[1]


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()


def assemble(browser_file, output, recovery=None, evaluation=None, comparison=None):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    browser = json.loads(Path(browser_file).read_bytes())
    if browser["outcome"] != "pass":
        raise ValueError("Cannot publish passing evidence from a failed browser run")
    files = git("ls-files", "--cached", "--others", "--exclude-standard").splitlines()
    fingerprints = {}
    for name in files:
        if "/reports/" in name or not (ROOT / name).is_file():
            continue
        fingerprints[name] = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
    report = {
        "scenario": "atlas-learning-center",
        "version": "atlas-v1",
        "source": {
            "commit": git("rev-parse", "HEAD"),
            "dirty": bool(git("status", "--porcelain")),
            "filesSha256": fingerprints,
        },
        "dataset": validate(),
        "accounts": [
            {"tenant": tenant, "role": role}
            for tenant, role in [
                ("Atlas", "OWNER"),
                ("Atlas", "ADMIN"),
                ("Atlas", "MEMBER"),
                ("Rif", "OWNER"),
            ]
        ],
        "browser": browser,
        "qualityBoundaries": {
            "retrieval": "Real multilingual E5 embeddings and PostgreSQL pgvector",
            "model": (
                "Existing synthetic test-only SDK transport through real LangGraph; "
                "not hosted intelligence"
            ),
            "business": "Actual HTTPS UI writes and persisted HTTP readbacks",
            "hostedQuality": "Not measured; no paid calls authorized",
            "humanUsability": "Not established by scripted task completion",
            "timing": (
                "Machine observed only, not human completion time "
                "or conversion evidence"
            ),
        },
    }
    latencies = sorted(request["elapsedMs"] for request in browser["requests"])
    report["httpMeasurements"] = {
        "definition": (
            "Browser-context explicit HTTP checks; wall time includes driver overhead"
        ),
        "count": len(latencies),
        "medianMs": statistics.median(latencies) if latencies else None,
        "p95Ms": latencies[math.ceil(len(latencies) * 0.95) - 1] if latencies else None,
        "statuses": dict(
            Counter(str(request["status"]) for request in browser["requests"])
        ),
    }
    report["ingestionMeasurements"] = [
        {"check": check["name"], "readyMs": check["readyMs"], "chunks": check["chunks"]}
        for check in browser["checks"]
        if "readyMs" in check
    ]
    queries = browser.get("retrieval", [])
    report["atlasRetrievalMetrics"] = {
        "cases": len(queries),
        "method": (
            "One expected document per query, first matching chunk rank in top five; "
            "separate from core-v1"
        ),
        "recallAt1": sum(q["rank"] == 1 for q in queries) / len(queries),
        "recallAt3": sum(0 < q["rank"] <= 3 for q in queries) / len(queries),
        "recallAt5": sum(0 < q["rank"] <= 5 for q in queries) / len(queries),
        "mrrAt5": sum(1 / q["rank"] if q["rank"] else 0 for q in queries)
        / len(queries),
    }
    for name, source in (
        ("recovery", recovery),
        ("coreEvaluation", evaluation),
        ("baselineComparison", comparison),
    ):
        if source:
            report[name] = json.loads(Path(source).read_bytes())
    screenshot_dir = output / "screenshots"
    screenshot_dir.mkdir(exist_ok=True)
    for name in browser["screenshots"]:
        if Path(name).name != name or not name.endswith(".png"):
            raise ValueError("Invalid screenshot artifact name")
        shutil.copyfile(Path(browser_file).parent / name, screenshot_dir / name)
    report["screenshotReview"] = (
        "See docs/product/pilot-usability-assessment.md for inspected evidence "
        "and limits. Capturing alone does not establish visual acceptance."
    )
    (output / "acceptance.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    metrics = report["atlasRetrievalMetrics"]
    lines = [
        "# Atlas synthetic business acceptance",
        "",
        f"Source: `{report['source']['commit']}`; "
        f"dirty local-test source: `{report['source']['dirty']}`.",
        "",
        f"Browser named workflow groups: {browser['actualTestCount']} passed. "
        f"Corpus: {report['dataset']['documents']} documents; "
        f"{metrics['cases']} Atlas retrieval queries.",
        "",
        f"Real Atlas retrieval Recall@1/3/5: {metrics['recallAt1']:.3f}/"
        f"{metrics['recallAt3']:.3f}/{metrics['recallAt5']:.3f}; "
        f"MRR@5: {metrics['mrrAt5']:.3f}.",
        "",
        "## Machine interaction counts",
        "",
        (
            "Counts include scripted click/fill/select/file interactions, "
            "not operator bootstrap commands or human thinking time. "
            "Counts include negative controls and repeat navigation/login; "
            "they are not minimum-click or human completion-time estimates."
        ),
        "",
        "| Journey | Interactions |",
        "| --- | ---: |",
    ]
    lines.extend(f"| {name} | {count} |" for name, count in browser["steps"].items())
    lines.extend(["", "## Workflow evidence", ""])
    lines.extend("- PASS: " + c["name"] for c in browser["checks"])
    lines.extend(
        [
            "",
            "## Limits",
            "",
            *["- " + value for value in report["qualityBoundaries"].values()],
        ]
    )
    (output / "acceptance.md").write_text("\n".join(lines) + "\n")
    shutil.copyfile(SCENARIO / "manifest.json", output / "dataset-manifest.json")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--recovery")
    parser.add_argument("--evaluation")
    parser.add_argument("--comparison")
    args = parser.parse_args()
    assemble(args.browser, args.output, args.recovery, args.evaluation, args.comparison)
    print("Sanitized JSON and Markdown evidence assembled; visual review is separate.")


if __name__ == "__main__":
    main()
