"""No Docker, models, credentials, or paid calls needed for these tests."""

import argparse
import copy
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from evaluation.dataset import load, strict_json
from evaluation.environment import Lab
from evaluation.metrics import fraction, latency, retrieval, summarize, unique_documents
from evaluation.reports import apply_review, compare, read, review_template, write
from evaluation.runner import controls, execute, selected_cases

DATASET = Path(__file__).resolve().parents[1] / "datasets/core-v1"


def args(**kwargs):
    return argparse.Namespace(
        **{
            "mode": "offline",
            "suite": "all",
            "dataset": str(DATASET),
            "output": "unused",
            "model_dir": "/unused",
            "provider": None,
            "model": None,
            "max_cases": None,
            "max_attempts": None,
            "max_budget": None,
            "cases": None,
            **kwargs,
        }
    )


class DatasetTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "dataset"
        shutil.copytree(DATASET, self.path)

    def mutate(self, change):
        path = self.path / "cases.json"
        data = json.loads(path.read_text())
        change(data)
        path.write_text(json.dumps(data))
        manifest = json.loads((self.path / "manifest.json").read_text())
        manifest["files"]["cases.json"] = hashlib.sha256(path.read_bytes()).hexdigest()
        (self.path / "manifest.json").write_text(json.dumps(manifest))

    def test_valid_version_hash_and_counts(self):
        result = load(self.path)
        self.assertEqual(len(result["cases"]), 72)
        self.assertEqual(len(result["hash"]), 64)
        self.assertEqual(result["manifest"]["counts"]["language"]["ary"], 11)

    def test_missing_required(self):
        self.mutate(lambda d: d["cases"][0].pop("question"))
        with self.assertRaises(ValueError):
            load(self.path)

    def test_duplicate_case(self):
        self.mutate(lambda d: d["cases"].append(d["cases"][0]))
        with self.assertRaises(ValueError):
            load(self.path)

    def test_unknown_label(self):
        self.mutate(lambda d: d["cases"][0].update(expectedBehavior="always_correct"))
        with self.assertRaises(ValueError):
            load(self.path)

    def test_unknown_expected_document(self):
        self.mutate(lambda d: d["cases"][0].update(expectedDocumentIds=["missing"]))
        with self.assertRaises(ValueError):
            load(self.path)

    def test_unauthorized_expected_document(self):
        self.mutate(
            lambda d: d["cases"][0].update(expectedDocumentIds=["other-tenant"])
        )
        with self.assertRaises(ValueError):
            load(self.path)

    def test_unknown_check(self):
        self.mutate(lambda d: d["cases"][-1].update(check="placeholder"))
        with self.assertRaises(ValueError):
            load(self.path)

    def test_hash_mismatch(self):
        (self.path / "fixtures/fees.txt").write_text("altered")
        with self.assertRaises(ValueError):
            load(self.path)

    def test_traversal(self):
        manifest = json.loads((self.path / "manifest.json").read_text())
        manifest["files"]["../private.txt"] = "fake"
        (self.path / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            load(self.path)

    def test_duplicate_json_and_malformed_unicode(self):
        for raw in ('{"a":1,"a":2}', '{"x":"\\ud800"}', '{"x":NaN}'):
            with self.assertRaises(ValueError):
                strict_json(raw)

    def test_unknown_schema(self):
        self.mutate(lambda d: d.update(schemaVersion=9))
        with self.assertRaises(ValueError):
            load(self.path)

    def test_boolean_is_not_schema_version_one(self):
        self.mutate(lambda d: d.update(schemaVersion=True))
        with self.assertRaises(ValueError):
            load(self.path)


class MetricsTest(unittest.TestCase):
    def test_rankings_multiple_relevant_and_failed_denominator(self):
        rows = [
            {"status": "ok", "relevant": ["a", "c"], "ranked": ["b", "a", "c"]},
            {"status": "execution-failure", "relevant": ["a"], "ranked": ["a"]},
        ]
        result = retrieval(rows)
        self.assertEqual(result["recall@1"]["value"], 0)
        self.assertEqual(result["recall@3"]["value"], 0.5)
        self.assertEqual(result["hitRate@3"]["value"], 0.5)
        self.assertAlmostEqual(result["precision@3"]["value"], 1 / 3)
        self.assertEqual(result["mrr"]["value"], 0.25)
        self.assertEqual(result["recall@5"]["denominator"], 2)

    def test_document_deduplication_and_short_result_precision(self):
        ranked = unique_documents(
            [{"documentId": "a"}, {"documentId": "a"}, {"documentId": "b"}]
        )
        self.assertEqual(ranked, ["a", "b"])
        self.assertEqual(
            retrieval([{"status": "ok", "relevant": ["a"], "ranked": ranked}])[
                "precision@5"
            ]["value"],
            0.2,
        )

    def test_empty_and_unlabeled(self):
        self.assertIsNone(retrieval([])["mrr"]["value"])
        self.assertEqual(
            retrieval([{"status": "ok", "relevant": ["a"], "ranked": []}])["mrr"][
                "value"
            ],
            0,
        )
        self.assertIsNone(retrieval([{"status": "ok", "relevant": []}])["mrr"]["value"])

    def test_generation_failure_does_not_erase_measured_retrieval(self):
        rows = [
            {
                "status": "agent-failure",
                "retrievalStatus": "ok",
                "relevant": ["a"],
                "ranked": ["a"],
            }
        ]
        self.assertEqual(retrieval(rows)["mrr"]["value"], 1)

    def test_not_run_boundaries_stay_in_denominator(self):
        rows = [
            {
                "status": "not-run",
                "category": "memory",
                "language": "en",
                "evidence": "none",
                "errorCategory": "setup-failure",
            }
        ]
        self.assertEqual(summarize(rows)["boundaryPassRate"], fraction(0, 1))

    def test_latency(self):
        self.assertIsNone(latency([1, 3])["p95Ms"])
        self.assertEqual(latency(list(range(1, 21)))["p95Ms"], 19)
        self.assertEqual(latency([1, 3])["medianMs"], 2)

    def test_mock_never_quality_or_real_usage(self):
        rows = [
            {
                "caseId": "x",
                "status": "ok",
                "language": "en",
                "evidence": "mock-contract",
                "run": {
                    "status": "SUCCEEDED",
                    "usage": {"inputTokens": 37, "outputTokens": 9},
                },
                "citationChecks": [True],
            }
        ]
        result = summarize(rows)
        self.assertEqual(result["knownUsage"]["runs"], 0)
        self.assertIsNone(result["structuralCitationValidity"]["value"])
        self.assertIsNone(result["reviewCorrectness"]["value"])

    def test_partial_returned_usage_keeps_unknown_direction_unknown(self):
        row = {
            "status": "agent-failure",
            "evidence": "hosted",
            "language": "en",
            "run": {"usage": {"inputTokens": 7, "outputTokens": None}},
        }
        usage = summarize([row])["knownUsage"]
        self.assertEqual(usage["inputTokens"], 7)
        self.assertIsNone(usage["outputTokens"])
        self.assertEqual(usage["outputRuns"], 0)
        self.assertIsNone(summarize([])["knownUsage"]["inputTokens"])

    def test_abstention_denominators(self):
        reviews = [
            {
                "correctness": 2,
                "groundedness": 2,
                "abstained": True,
                "language": "en",
                "supportedClaims": 0,
                "unsupportedClaims": 0,
                "incompleteCitations": False,
                "injectionSucceeded": None,
                "expectedBehavior": "abstain",
            },
            {
                "correctness": 0,
                "groundedness": 0,
                "abstained": True,
                "language": "fr",
                "supportedClaims": 0,
                "unsupportedClaims": 0,
                "incompleteCitations": True,
                "injectionSucceeded": None,
                "expectedBehavior": "grounded_answer",
            },
        ]
        result = summarize([], reviews)
        self.assertEqual(result["correctAbstentionRate"], fraction(1, 1))
        self.assertEqual(result["falseAbstentionRate"], fraction(1, 1))


class ControlsTest(unittest.TestCase):
    def test_hosted_disabled_even_with_key(self):
        with (
            patch.dict(os.environ, {"OPENAI_API_KEY": "synthetic-only"}, clear=True),
            self.assertRaises(ValueError),
        ):
            controls(args(mode="hosted"))

    def configured(self, **kw):
        from evaluation.environment import MODEL

        return args(
            mode="hosted",
            suite="generation",
            provider="openai",
            model=MODEL,
            max_cases=16,
            max_attempts=17,
            **kw,
        )

    def test_explicit_controls(self):
        with patch.dict(
            os.environ,
            {"EAAP_EVAL_ALLOW_HOSTED": "true", "OPENAI_API_KEY": "synthetic-only"},
            clear=True,
        ):
            controls(self.configured())
            for setting in (
                {"max_cases": None},
                {"max_attempts": 21},
                {"provider": "unknown"},
                {"model": "other"},
                {"max_budget": 10},
            ):
                configured = self.configured()
                for key, value in setting.items():
                    setattr(configured, key, value)
                with self.assertRaises(ValueError):
                    controls(configured)

    def test_case_and_attempt_caps_include_seed(self):
        configured = self.configured()
        cases = selected_cases(load(DATASET), configured)
        self.assertEqual(len(cases), 16)
        configured.max_attempts = 16
        with self.assertRaises(ValueError):
            selected_cases(load(DATASET), configured)
        configured.max_attempts = 17
        configured.max_cases = 1
        with self.assertRaises(ValueError):
            selected_cases(load(DATASET), configured)

    def test_unknown_case_rejected(self):
        with self.assertRaises(ValueError):
            selected_cases(load(DATASET), args(cases="unknown"))

    def test_no_target_override(self):
        result = subprocess.run(
            [
                "python3",
                "-m",
                "evaluation",
                "run",
                "--dataset",
                str(DATASET),
                "--suite",
                "all",
                "--model-dir",
                "/unused",
                "--output",
                "/unused-output",
                "--target",
                "https://production.example",
            ],
            capture_output=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"unrecognized arguments: --target", result.stderr)

    def test_missing_dataset_and_output_preservation(self):
        with self.assertRaises(FileNotFoundError):
            load("/not-an-evaluation-dataset")
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "user.txt").write_text("preserve")
            with self.assertRaises(ValueError):
                execute(args(output=directory))
            self.assertEqual((Path(directory) / "user.txt").read_text(), "preserve")

    def test_offline_provider_is_cleared_and_unique_project(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.dict(
                os.environ,
                {
                    "OPENAI_API_KEY": "synthetic-bait",
                    "DB_HOST": "production.example",
                    "COMPOSE_PROJECT_NAME": "userproject",
                },
            ),
        ):
            lab = Lab(directory)
            try:
                self.assertEqual(lab.env["OPENAI_API_KEY"], "")
                self.assertEqual(lab.env["DB_HOST"], "postgres")
                self.assertNotIn("COMPOSE_PROJECT_NAME", lab.env)
                self.assertTrue(lab.project.startswith("eaap-eval-"))
            finally:
                lab.close()

    def test_interrupted_run_truthfully_reports_all_cases(self):
        class InterruptedLab:
            project = "synthetic-test"

            def __init__(self, *a, **kw):
                pass

            def start(self):
                raise KeyboardInterrupt()

            def close(self):
                pass

        with tempfile.TemporaryDirectory() as directory:
            report = execute(args(output=directory), InterruptedLab)
            self.assertEqual(report["status"], "INTERRUPTED")
            self.assertEqual(len(report["cases"]), 72)
            self.assertEqual(report["metrics"]["retrieval"]["mrr"]["denominator"], 44)
            self.assertEqual(report["paidCalls"], 0)
            self.assertTrue((Path(directory) / "report.md").exists())


class ReviewTest(unittest.TestCase):
    def report(self, evidence="hosted"):
        return {
            "experimentId": "synthetic-run",
            "dataset": {"hash": "hash"},
            "cases": [
                {
                    "caseId": "a",
                    "evidence": evidence,
                    "language": "en",
                    "expectedBehavior": "grounded_answer",
                    "run": {"status": "SUCCEEDED"},
                }
            ],
        }

    def form(self):
        form = review_template(self.report())
        form["reviewer"] = "synthetic-reviewer"
        form["cases"][0].update(
            correctness=1,
            groundedness=0,
            abstained=False,
            supportedClaims=0,
            unsupportedClaims=1,
            incompleteCitations=True,
            rationale="Structurally valid source does not support the claim.",
        )
        return form

    def test_separate_scores_roundtrip(self):
        report = apply_review(self.report(), self.form())
        self.assertEqual(report["reviews"][0]["correctness"], 1)
        self.assertEqual(report["reviews"][0]["groundedness"], 0)
        self.assertEqual(json.loads(json.dumps(report)), report)

    def test_mock_cannot_be_reviewed_as_hosted(self):
        with self.assertRaises(ValueError):
            apply_review(self.report("mock-contract"), self.form())

    def test_timeout_not_abstention(self):
        report = self.report()
        report["cases"][0]["run"] = {
            "status": "FAILED",
            "failureCode": "PROVIDER_TIMEOUT",
        }
        with self.assertRaises(ValueError):
            apply_review(report, self.form())

    def test_bad_score_and_missing_rationale(self):
        for mutation in (
            {"correctness": 3},
            {"groundedness": True},
            {"rationale": ""},
            {"supportedClaims": -1},
        ):
            form = self.form()
            form["cases"][0].update(mutation)
            with self.assertRaises(ValueError):
                apply_review(self.report(), form)

    def test_duplicate_or_mismatched_provenance(self):
        form = self.form()
        form["cases"].append(copy.deepcopy(form["cases"][0]))
        with self.assertRaises(ValueError):
            apply_review(self.report(), form)
        form = self.form()
        form["datasetHash"] = "wrong"
        with self.assertRaises(ValueError):
            apply_review(self.report(), form)

    def test_comparison_compatibility(self):
        baseline = {
            "dataset": {"hash": "a"},
            "configuration": {"mode": "offline"},
            "cases": [],
            "metrics": {"retrieval": {"mrr": fraction(1, 2)}},
        }
        candidate = copy.deepcopy(baseline)
        candidate["metrics"]["retrieval"]["mrr"] = fraction(2, 2)
        result = compare(baseline, candidate)
        self.assertTrue(result["compatible"])
        self.assertEqual(result["metrics"][0]["absoluteChange"], 0.5)
        self.assertEqual(result["metrics"][0]["relativeChange"], 1)
        candidate["dataset"]["hash"] = "b"
        self.assertFalse(compare(baseline, candidate)["compatible"])

    def test_security_gate(self):
        report = {
            "dataset": {},
            "configuration": {},
            "metrics": {},
            "cases": [{"evidence": "mock-contract", "status": "execution-failure"}],
        }
        self.assertTrue(compare(report, report)["securityRegression"])

    def test_report_from_actual_counts(self):
        report = {
            "experimentId": "test",
            "status": "COMPLETE",
            "hostedInferenceOccurred": False,
            "startedAt": "test",
            "commit": "test",
            "dirty": True,
            "sourceHash": "test",
            "dataset": {"version": "test", "hash": "test"},
            "cases": [
                {
                    "caseId": "test",
                    "language": "en",
                    "status": "ok",
                    "evidence": "retrieval",
                    "relevant": ["a"],
                    "ranked": ["a"],
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            write(report, directory)
            self.assertEqual(
                read(Path(directory) / "results.json")["metrics"]["counts"], {"ok": 1}
            )
            self.assertIn(
                "Synthetic evaluation", (Path(directory) / "report.md").read_text()
            )


if __name__ == "__main__":
    unittest.main()
