"""Validate the separately versioned, entirely synthetic Atlas business corpus."""

import hashlib
import json
from pathlib import Path

SCENARIO = Path(__file__).resolve().parent / "scenarios/atlas-learning-center"


def validate(directory=SCENARIO):
    directory = Path(directory)
    manifest = json.loads((directory / "manifest.json").read_bytes())
    if manifest["version"] != "atlas-v1" or manifest["synthetic"] is not True:
        raise ValueError("Only the reviewed synthetic Atlas dataset is accepted")
    docs = manifest["documents"]
    names = [d["filename"] for d in docs]
    if len(names) != len(set(names)) or len(docs) != 12:
        raise ValueError("Expected twelve unique fixture documents")
    if {p.name for p in (directory / "fixtures").iterdir()} != set(names):
        raise ValueError("Fixture inventory differs from its manifest")
    for doc in docs:
        name = doc["filename"]
        if Path(name).name != name or not name.endswith((".txt", ".pdf")):
            raise ValueError("Invalid fixture path or media")
        data = (directory / "fixtures" / name).read_bytes()
        if (
            hashlib.sha256(data).hexdigest() != doc["sha256"]
            or len(data) != doc["bytes"]
        ):
            raise ValueError("Fixture content changed without manifest update")
        if not doc["expectedFacts"] or not all(
            isinstance(f, str) and f.strip() for f in doc["expectedFacts"]
        ):
            raise ValueError("Expected facts must be independently inspectable")
        if name.endswith(".txt"):
            data.decode("utf-8", errors="strict")
        elif not data.startswith(b"%PDF-") or b"%%EOF" not in data:
            raise ValueError("PDF fixture envelope missing")
    cases = json.loads((directory / "retrieval-cases.json").read_bytes())
    if len(cases) != 10 or len({c["id"] for c in cases}) != 10:
        raise ValueError("Expected ten separate business retrieval cases")
    atlas = {d["filename"] for d in docs if d["tenant"] == "atlas"}
    if any(c["expectedFilename"] not in atlas or not c["query"] for c in cases):
        raise ValueError("Retrieval expectation is not an Atlas fixture")
    if {c["language"] for c in cases} != {"en", "fr", "ar", "darija"}:
        raise ValueError("Multilingual coverage missing")
    return {
        "version": manifest["version"],
        "documents": len(docs),
        "cases": len(cases),
        "manifestSha256": hashlib.sha256(
            (directory / "manifest.json").read_bytes()
        ).hexdigest(),
    }


if __name__ == "__main__":
    print(json.dumps(validate(), indent=2))
