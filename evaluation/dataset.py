"""Strict, dependency-free validation of the versioned benchmark format."""

import hashlib
import json
from collections import Counter
from pathlib import Path

LANGUAGES = {"en", "fr", "ar", "ary"}
CATEGORIES = {
    "retrieval",
    "supported",
    "unanswerable",
    "conflict",
    "injection",
    "citation",
    "memory",
    "authorization",
}
BEHAVIORS = {
    "grounded_answer",
    "abstain",
    "clarify",
    "resist_injection",
    "reject",
    "history_contract",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key] = value
        return result

    value = json.loads(
        raw, object_pairs_hook=pairs, parse_constant=lambda _: fail("Nonfinite JSON")
    )
    # UTF-8 rejects lone surrogate escapes, including those in JSON keys.
    json.dumps(value, ensure_ascii=False).encode("utf-8", errors="strict")
    return value


def fail(message):
    raise ValueError(message)


def keys(value, required):
    if not isinstance(value, dict) or set(value) != set(required.split()):
        fail("Unexpected or missing fields")


def strings(value, maximum=10000):
    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > maximum
        or "\x00" in value
    ):
        fail("Invalid text")


def load(path):
    path = Path(path).resolve()
    raw = (path / "cases.json").read_bytes()
    manifest = strict_json((path / "manifest.json").read_bytes())
    keys(
        manifest,
        "schemaVersion datasetVersion fixtureVersion languages "
        "counts labeling weaknesses files",
    )
    if (
        manifest["schemaVersion"] != 1
        or type(manifest["schemaVersion"]) is not int
        or manifest["datasetVersion"] != "core-v1"
        or manifest["fixtureVersion"] != 1
        or type(manifest["fixtureVersion"]) is not int
    ):
        fail("Unsupported dataset version")
    if not isinstance(manifest["languages"], list) or (
        set(manifest["languages"]) != LANGUAGES or len(manifest["languages"]) != 4
    ):
        fail("Invalid languages")
    strings(manifest["labeling"])
    if not isinstance(manifest["weaknesses"], list) or not manifest["weaknesses"]:
        fail("Missing limitations")
    for weakness in manifest["weaknesses"]:
        strings(weakness)
    if not isinstance(manifest["files"], dict) or "cases.json" not in manifest["files"]:
        fail("Missing hashes")
    for name, expected in manifest["files"].items():
        target = (path / name).resolve()
        if (
            not target.is_relative_to(path)
            or not target.is_file()
            or digest(target.read_bytes()) != expected
        ):
            fail("Dataset file integrity failure")
    dataset = strict_json(raw)
    keys(dataset, "schemaVersion documents cases")
    if (
        dataset["schemaVersion"] != 1
        or type(dataset["schemaVersion"]) is not int
        or not isinstance(dataset["documents"], list)
        or not isinstance(dataset["cases"], list)
    ):
        fail("Invalid schema")
    documents = {}
    for doc in dataset["documents"]:
        keys(doc, "id file scope")
        strings(doc["id"], 80)
        if doc["id"] in documents or doc["scope"] not in {
            "associated",
            "unassociated",
            "other-tenant",
        }:
            fail("Invalid document identity/scope")
        if doc["file"] not in manifest["files"] or not doc["file"].startswith(
            "fixtures/"
        ):
            fail("Unhashed fixture")
        documents[doc["id"]] = doc
    seen = set()
    for case in dataset["cases"]:
        keys(
            case,
            "caseId category language question expectedDocumentIds "
            "expectedEvidence expectedBehavior tags check",
        )
        strings(case["caseId"], 80)
        strings(case["question"], 1000)
        if case["caseId"] in seen:
            fail("Duplicate case ID")
        seen.add(case["caseId"])
        if (
            case["category"] not in CATEGORIES
            or case["language"] not in LANGUAGES
            or case["expectedBehavior"] not in BEHAVIORS
        ):
            fail("Unsupported label")
        for name in ("expectedDocumentIds", "expectedEvidence", "tags"):
            if not isinstance(case[name], list) or len(case[name]) != len(
                set(case[name])
            ):
                fail("Invalid label list")
            for item in case[name]:
                strings(item)
        if any(
            ref not in documents or documents[ref]["scope"] != "associated"
            for ref in case["expectedDocumentIds"]
        ):
            fail("Invalid expected document reference")
        if case["category"] == "retrieval" and not case["expectedDocumentIds"]:
            fail("Unlabeled retrieval case")
        if not isinstance(case["check"], str):
            fail("Invalid boundary check")
        if case["category"] in {"memory", "authorization", "citation"}:
            from evaluation.scenarios import CHECKS

            if case["check"] not in CHECKS:
                fail("Unknown deterministic check")
        elif case["check"]:
            fail("Unexpected deterministic check")
    counts = {
        "category": dict(Counter(c["category"] for c in dataset["cases"])),
        "language": dict(Counter(c["language"] for c in dataset["cases"])),
    }
    if manifest["counts"] != counts:
        fail("Manifest counts disagree")
    return {
        "path": path,
        "manifest": manifest,
        "documents": documents,
        "cases": dataset["cases"],
        "hash": digest((path / "manifest.json").read_bytes()),
    }
