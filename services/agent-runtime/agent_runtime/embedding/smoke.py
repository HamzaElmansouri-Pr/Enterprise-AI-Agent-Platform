"""Separate real-model check; never part of default pytest or an implicit download."""

import math
import resource
import sys
import time

from agent_runtime.embedding.model import MODEL_ID, LocalEmbeddings


def smoke(directory: str):
    started = time.monotonic()
    adapter = LocalEmbeddings(directory)
    passages = adapter.embed(
        "passage",
        [
            "Applicants need a passport and academic transcripts.",
            "Les candidats doivent fournir un passeport.",
            "يجب على المتقدمين تقديم جواز السفر.",
        ],
    )
    queries = adapter.embed("query", ["What documents do applicants need?"])
    assert len(passages) == 3 and len(queries) == 1
    for vector in passages + queries:
        assert len(vector) == 384 and all(math.isfinite(x) for x in vector)
        assert abs(sum(x * x for x in vector) - 1) < 0.001
    scores = [sum(x * y for x, y in zip(v, queries[0], strict=True)) for v in passages]
    print(f"Real CPU adapter passed: {MODEL_ID}")
    print(f"Elapsed seconds: {time.monotonic() - started:.3f}")
    print(f"Peak RSS KiB: {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss}")
    print(f"Synthetic passage cosine scores: {scores}")


if __name__ == "__main__":
    smoke(sys.argv[1])
