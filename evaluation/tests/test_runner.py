"""Boundary and opt-in execution plumbing with test-only transports."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from evaluation.dataset import load
from evaluation.environment import MODEL, NoRedirect
from evaluation.runner import execute, generate, search
from evaluation.tests.test_evaluation import DATASET, args


class SyntheticAPI:
    agent_path = "/tenants/synthetic/agents/synthetic"

    def __init__(self, failure=False):
        self.calls = []
        self.failure = failure

    def api(self, method, path, body=None, **kwargs):
        self.calls.append((method, path, body))
        if path.endswith("/conversations"):
            return {"id": "synthetic-conversation"}
        if path.endswith("/messages"):
            if self.failure:
                raise TimeoutError("synthetic transport failure")
            return {
                "result": {
                    "runId": "synthetic-run",
                    "status": "SUCCEEDED",
                    "modelId": MODEL,
                    "answer": "Synthetic only",
                    "citations": [
                        {
                            "documentId": "doc",
                            "chunkId": "chunk",
                            "filename": "synthetic.txt",
                            "sourcePage": None,
                        }
                    ],
                    "usage": {"inputTokens": 5, "outputTokens": 3},
                }
            }
        if path.endswith("/knowledge/search"):
            return {
                "results": [
                    {
                        "documentId": "doc",
                        "chunkId": "chunk",
                        "filename": "synthetic.txt",
                        "sourcePage": None,
                        "score": 0.5,
                        "text": "Synthetic only",
                    }
                ]
            }
        return {}


class RunnerTest(unittest.TestCase):
    def case(self, tags=None):
        return {
            "caseId": "synthetic",
            "question": "Synthetic question?",
            "tags": tags or [],
        }

    def test_hosted_dispatch_uses_only_application_api(self):
        lab = SyntheticAPI()
        attempts = [0]
        result = generate(lab, self.case(), {}, attempts, 1)
        self.assertEqual(attempts, [1])
        self.assertEqual(result["citationChecks"], [True])
        self.assertEqual(len([c for c in lab.calls if c[1].endswith("/messages")]), 1)
        self.assertTrue(all(path.startswith("/tenants/") for _, path, _ in lab.calls))
        self.assertTrue(
            all(
                "requestId" in body
                for _, path, body in lab.calls
                if path.endswith("/messages")
            )
        )

    def test_no_retry_on_ambiguous_timeout(self):
        lab = SyntheticAPI(failure=True)
        attempts = [0]
        with self.assertRaises(TimeoutError):
            generate(lab, self.case(), {}, attempts, 1)
        self.assertEqual(attempts[0], 1)
        self.assertEqual(len([c for c in lab.calls if c[1].endswith("/messages")]), 1)

    def test_attempt_cap_prevents_second_paid_dispatch(self):
        lab = SyntheticAPI()
        attempts = [0]
        with self.assertRaises(ValueError):
            generate(lab, self.case(["prior-turn-authority"]), {}, attempts, 1)
        self.assertEqual(attempts, [1])
        self.assertEqual(len([c for c in lab.calls if c[1].endswith("/messages")]), 1)

    def test_seed_usage_retained_if_followup_fails(self):
        lab = SyntheticAPI()
        outcome = {}
        with self.assertRaises(ValueError):
            generate(lab, self.case(["prior-turn-authority"]), {}, [0], 1, outcome)
        self.assertEqual(outcome["auxiliaryRuns"][0]["usage"]["inputTokens"], 5)

    def test_wrong_tenant_result_rejected(self):
        with self.assertRaises(ValueError):
            search(
                SyntheticAPI(),
                self.case(),
                {"fixture": {"id": "other-doc", "scope": "associated"}},
            )

    def test_unassociated_result_rejected(self):
        with self.assertRaises(ValueError):
            search(
                SyntheticAPI(),
                self.case(),
                {"fixture": {"id": "doc", "scope": "unassociated"}},
            )

    def test_source_metadata_and_score_integrity(self):
        result = search(
            SyntheticAPI(),
            self.case(),
            {
                "fixture": {
                    "id": "doc",
                    "filename": "synthetic.txt",
                    "scope": "associated",
                }
            },
        )
        self.assertEqual(result["ranked"], ["fixture"])
        self.assertEqual(result["retrieved"][0]["score"], 0.5)
        self.assertEqual(result["retrievalStatus"], "ok")

    def test_redirect_forbidden(self):
        with self.assertRaises(ValueError):
            NoRedirect().redirect_request(
                None, None, 302, "", {}, "https://external.example"
            )

    def test_sensitive_failure_body_not_in_report(self):
        class FailedLab:
            project = "synthetic-owned"

            def __init__(self, *a, **kw):
                pass

            def start(self):
                raise RuntimeError("SYNTHETIC_SECRET_BAIT_DO_NOT_PRINT")

            def close(self):
                pass

        with tempfile.TemporaryDirectory() as directory:
            report = execute(args(output=directory), FailedLab)
            self.assertEqual(report["status"], "FAILED")
            raw = (Path(directory) / "results.json").read_text()
            self.assertNotIn("SYNTHETIC_SECRET_BAIT_DO_NOT_PRINT", raw)
            self.assertEqual(json.loads(raw)["metrics"]["counts"]["not-run"], 72)

    def test_default_hosted_rejected_before_environment_setup(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaises(ValueError):
                execute(args(mode="hosted"), lambda *_: self.fail("Must not provision"))

    def test_safety_case_list_is_complete(self):
        from evaluation.scenarios import CHECKS

        self.assertEqual(
            {c["check"] for c in load(DATASET)["cases"] if c["check"]}, CHECKS
        )


if __name__ == "__main__":
    unittest.main()
