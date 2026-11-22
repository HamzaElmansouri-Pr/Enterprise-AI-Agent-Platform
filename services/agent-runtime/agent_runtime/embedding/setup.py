"""Explicit network-enabled model setup: python -m agent_runtime.embedding.setup DIR."""

import hashlib
import json
import sys
from pathlib import Path
from urllib.request import urlopen

from agent_runtime.embedding.model import FILES, MODEL, MODEL_ID, REVISION


def setup(directory: str):
    target = Path(directory)
    target.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for name in FILES:
        url = f"https://huggingface.co/{MODEL}/resolve/{REVISION}/onnx/{name}"
        temporary = target / (name + ".partial")
        with urlopen(url, timeout=180) as response, temporary.open("wb") as out:
            while block := response.read(1024 * 1024):
                out.write(block)
        temporary.replace(target / name)
        with (target / name).open("rb") as stream:
            hashes[name] = hashlib.file_digest(stream, "sha256").hexdigest()
    (target / "manifest.json").write_text(
        json.dumps({"modelId": MODEL_ID, "sha256": hashes})
    )
    print(f"Prepared {MODEL_ID}; startup requires no network")


if __name__ == "__main__":
    setup(sys.argv[1])
