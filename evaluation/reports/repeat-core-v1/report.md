# Synthetic evaluation eval-4947677891e14791b33c7bc952a2e6e2

Status: COMPLETE; hosted inference: False
Date: 2026-11-22T15:21:07.846991+00:00; commit: `689b4c94090f6d2478771830cd90ea3d2d0bbdb9`; dirty: True
Dataset: core-v1 / `97c17c81b57feb9a24e820faa2251e95cad7775a63142607ae3e01236502487b`
Source fingerprint: `527f10121e38e80ae6c9d323c06ca0e42c4778d33745a442efa0b7a8600660a9`

## Configuration

```json
{
  "mode": "offline",
  "suite": "all",
  "embedding": "intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3",
  "provider": null,
  "model": null,
  "maxCases": null,
  "maxAttempts": null,
  "caseIds": [
    "retrieval-en-001",
    "retrieval-en-002",
    "retrieval-en-003",
    "retrieval-en-004",
    "retrieval-en-005",
    "retrieval-en-006",
    "retrieval-en-007",
    "retrieval-en-008",
    "retrieval-fr-001",
    "retrieval-fr-002",
    "retrieval-fr-003",
    "retrieval-fr-004",
    "retrieval-fr-005",
    "retrieval-fr-006",
    "retrieval-fr-007",
    "retrieval-fr-008",
    "retrieval-ar-001",
    "retrieval-ar-002",
    "retrieval-ar-003",
    "retrieval-ar-004",
    "retrieval-ar-005",
    "retrieval-ar-006",
    "retrieval-ar-007",
    "retrieval-ar-008",
    "retrieval-ary-001",
    "retrieval-ary-002",
    "retrieval-ary-003",
    "retrieval-ary-004",
    "retrieval-ary-005",
    "retrieval-ary-006",
    "retrieval-ary-007",
    "retrieval-ary-008",
    "supported-en-001",
    "supported-fr-001",
    "supported-ar-001",
    "supported-ary-001",
    "unanswerable-en-001",
    "unanswerable-fr-001",
    "unanswerable-ar-001",
    "unanswerable-ary-001",
    "conflict-en-001",
    "conflict-fr-001",
    "injection-en-001",
    "injection-fr-001",
    "injection-ar-001",
    "injection-ary-001",
    "injection-en-002",
    "injection-en-003",
    "memory-en-001",
    "citation-en-001",
    "memory-en-002",
    "memory-en-003",
    "authorization-en-001",
    "memory-en-004",
    "memory-en-005",
    "memory-en-006",
    "memory-en-007",
    "citation-en-002",
    "authorization-en-002",
    "authorization-en-003",
    "citation-en-003",
    "memory-en-008",
    "memory-en-009",
    "memory-en-010",
    "memory-en-011",
    "authorization-en-004",
    "authorization-en-005",
    "authorization-en-006",
    "authorization-en-007",
    "authorization-en-008",
    "memory-en-012",
    "citation-en-004"
  ],
  "ranking": "first 10 chunks, deduplicate documents preserving first occurrence",
  "fixtureIsolation": "fresh-owned-compose-v1"
}
```

Real retrieval and deterministic boundaries are separate from provider quality. Mock transport is contract evidence only. Human correctness and semantic citation support are unmeasured until reviewed.

## Counts

```json
{
  "ok": 72
}
{
  "category": {
    "retrieval": 32,
    "supported": 4,
    "unanswerable": 4,
    "conflict": 2,
    "injection": 6,
    "memory": 12,
    "citation": 4,
    "authorization": 8
  },
  "language": {
    "en": 38,
    "fr": 12,
    "ar": 11,
    "ary": 11
  }
}
```

## Metrics (explicit numerators and denominators)

```json
{
  "retrieval": {
    "recall@1": {
      "value": 0.75,
      "numerator": 33.0,
      "denominator": 44
    },
    "hitRate@1": {
      "value": 0.8181818181818182,
      "numerator": 36,
      "denominator": 44
    },
    "precision@1": {
      "value": 0.8181818181818182,
      "numerator": 36.0,
      "denominator": 44
    },
    "recall@3": {
      "value": 0.9431818181818182,
      "numerator": 41.5,
      "denominator": 44
    },
    "hitRate@3": {
      "value": 0.9545454545454546,
      "numerator": 42,
      "denominator": 44
    },
    "precision@3": {
      "value": 0.3560606060606061,
      "numerator": 15.666666666666668,
      "denominator": 44
    },
    "recall@5": {
      "value": 0.9772727272727273,
      "numerator": 43.0,
      "denominator": 44
    },
    "hitRate@5": {
      "value": 0.9772727272727273,
      "numerator": 43,
      "denominator": 44
    },
    "precision@5": {
      "value": 0.22272727272727275,
      "numerator": 9.8,
      "denominator": 44
    },
    "mrr": {
      "value": 0.8896464646464647,
      "numerator": 39.144444444444446,
      "denominator": 44
    }
  },
  "retrievalByLanguage": {
    "ar": {
      "recall@1": {
        "value": 0.85,
        "numerator": 8.5,
        "denominator": 10
      },
      "hitRate@1": {
        "value": 0.9,
        "numerator": 9,
        "denominator": 10
      },
      "precision@1": {
        "value": 0.9,
        "numerator": 9.0,
        "denominator": 10
      },
      "recall@3": {
        "value": 0.9,
        "numerator": 9.0,
        "denominator": 10
      },
      "hitRate@3": {
        "value": 0.9,
        "numerator": 9,
        "denominator": 10
      },
      "precision@3": {
        "value": 0.3333333333333333,
        "numerator": 3.333333333333333,
        "denominator": 10
      },
      "recall@5": {
        "value": 1.0,
        "numerator": 10.0,
        "denominator": 10
      },
      "hitRate@5": {
        "value": 1.0,
        "numerator": 10,
        "denominator": 10
      },
      "precision@5": {
        "value": 0.22000000000000003,
        "numerator": 2.2,
        "denominator": 10
      },
      "mrr": {
        "value": 0.9199999999999999,
        "numerator": 9.2,
        "denominator": 10
      }
    },
    "ary": {
      "recall@1": {
        "value": 0.75,
        "numerator": 7.5,
        "denominator": 10
      },
      "hitRate@1": {
        "value": 0.8,
        "numerator": 8,
        "denominator": 10
      },
      "precision@1": {
        "value": 0.8,
        "numerator": 8.0,
        "denominator": 10
      },
      "recall@3": {
        "value": 0.95,
        "numerator": 9.5,
        "denominator": 10
      },
      "hitRate@3": {
        "value": 1.0,
        "numerator": 10,
        "denominator": 10
      },
      "precision@3": {
        "value": 0.33333333333333337,
        "numerator": 3.3333333333333335,
        "denominator": 10
      },
      "recall@5": {
        "value": 1.0,
        "numerator": 10.0,
        "denominator": 10
      },
      "hitRate@5": {
        "value": 1.0,
        "numerator": 10,
        "denominator": 10
      },
      "precision@5": {
        "value": 0.22000000000000003,
        "numerator": 2.2,
        "denominator": 10
      },
      "mrr": {
        "value": 0.9,
        "numerator": 9.0,
        "denominator": 10
      }
    },
    "en": {
      "recall@1": {
        "value": 0.7692307692307693,
        "numerator": 10.0,
        "denominator": 13
      },
      "hitRate@1": {
        "value": 0.8461538461538461,
        "numerator": 11,
        "denominator": 13
      },
      "precision@1": {
        "value": 0.8461538461538461,
        "numerator": 11.0,
        "denominator": 13
      },
      "recall@3": {
        "value": 1.0,
        "numerator": 13.0,
        "denominator": 13
      },
      "hitRate@3": {
        "value": 1.0,
        "numerator": 13,
        "denominator": 13
      },
      "precision@3": {
        "value": 0.3846153846153845,
        "numerator": 4.999999999999999,
        "denominator": 13
      },
      "recall@5": {
        "value": 1.0,
        "numerator": 13.0,
        "denominator": 13
      },
      "hitRate@5": {
        "value": 1.0,
        "numerator": 13,
        "denominator": 13
      },
      "precision@5": {
        "value": 0.2307692307692308,
        "numerator": 3.0000000000000004,
        "denominator": 13
      },
      "mrr": {
        "value": 0.9102564102564104,
        "numerator": 11.833333333333334,
        "denominator": 13
      }
    },
    "fr": {
      "recall@1": {
        "value": 0.6363636363636364,
        "numerator": 7.0,
        "denominator": 11
      },
      "hitRate@1": {
        "value": 0.7272727272727273,
        "numerator": 8,
        "denominator": 11
      },
      "precision@1": {
        "value": 0.7272727272727273,
        "numerator": 8.0,
        "denominator": 11
      },
      "recall@3": {
        "value": 0.9090909090909091,
        "numerator": 10.0,
        "denominator": 11
      },
      "hitRate@3": {
        "value": 0.9090909090909091,
        "numerator": 10,
        "denominator": 11
      },
      "precision@3": {
        "value": 0.3636363636363636,
        "numerator": 3.9999999999999996,
        "denominator": 11
      },
      "recall@5": {
        "value": 0.9090909090909091,
        "numerator": 10.0,
        "denominator": 11
      },
      "hitRate@5": {
        "value": 0.9090909090909091,
        "numerator": 10,
        "denominator": 11
      },
      "precision@5": {
        "value": 0.21818181818181817,
        "numerator": 2.4,
        "denominator": 11
      },
      "mrr": {
        "value": 0.8282828282828283,
        "numerator": 9.11111111111111,
        "denominator": 11
      }
    }
  },
  "boundaryPassRate": {
    "value": 1.0,
    "numerator": 24,
    "denominator": 24
  },
  "structuralCitationValidity": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "semanticSupport": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "incompleteCitationReviewRate": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "injectionSuccessReviewRate": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "missingCitationOutputs": 0,
  "reviewCorrectness": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "reviewCoverage": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "reviewGroundedness": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "abstentionRate": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "correctAbstentionRate": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "falseAbstentionRate": {
    "value": null,
    "numerator": 0,
    "denominator": 0
  },
  "reviewByLanguage": {},
  "latency": {
    "count": 48,
    "medianMs": 76.93622649821918,
    "p95Ms": 96.99965501204133
  },
  "knownUsage": {
    "runs": 0,
    "inputRuns": 0,
    "outputRuns": 0,
    "inputTokens": null,
    "outputTokens": null
  },
  "errors": {},
  "counts": {
    "ok": 72
  }
}
```

## Failures and representative outcomes

- {"caseId": "retrieval-en-001", "status": "ok", "evidence": "retrieval", "relevant": ["registration"], "ranked": ["visa", "registration", "attack-txt", "support", "scholarship", "language", "fees", "housing", "calendar-conflict", "calendar"]}
- {"caseId": "retrieval-fr-004", "status": "ok", "evidence": "retrieval", "relevant": ["language"], "ranked": ["registration", "language", "calendar-conflict", "support", "visa", "calendar", "attack-txt", "scholarship", "housing", "attack-pdf"]}
- {"caseId": "retrieval-fr-005", "status": "ok", "evidence": "retrieval", "relevant": ["housing"], "ranked": ["fees", "registration", "support", "scholarship", "calendar", "calendar-conflict", "visa", "language", "housing", "attack-txt"]}
- {"caseId": "retrieval-en-001", "status": "ok", "evidence": "retrieval", "relevant": ["registration"], "ranked": ["visa", "registration", "attack-txt", "support", "scholarship", "language", "fees", "housing", "calendar-conflict", "calendar"]}
- {"caseId": "retrieval-en-002", "status": "ok", "evidence": "retrieval", "relevant": ["fees"], "ranked": ["fees", "housing", "scholarship", "support", "language", "calendar", "registration", "calendar-conflict", "visa", "attack-txt"]}
- {"caseId": "retrieval-en-003", "status": "ok", "evidence": "retrieval", "relevant": ["scholarship"], "ranked": ["scholarship", "fees", "visa", "registration", "housing", "language", "support", "attack-txt", "calendar-conflict", "calendar"]}

## Limits

Small synthetic convenience sample; language estimates are unstable. No statistical significance is inferred. Scores are similarities, not probabilities. Latency includes HTTP overhead and is not a load benchmark. p95 uses nearest rank and is omitted below 20 samples. Unknown usage stays unknown. Billing totals are unavailable. Dataset labels and Darija phrasing need independent review. No paid inference occurs in offline mode.

Configuration and individual measured outcomes are in results.json.
