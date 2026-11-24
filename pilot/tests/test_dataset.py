import shutil
import tempfile
import unittest
from pathlib import Path

from pilot.dataset import SCENARIO, validate


class BusinessFixturesTest(unittest.TestCase):
    def test_separate_multilingual_business_corpus_is_valid(self):
        self.assertEqual(validate()["cases"], 10)
        self.assertEqual(validate()["documents"], 12)

    def test_changed_document_cannot_silently_reuse_labels(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "corpus"
            shutil.copytree(SCENARIO, target)
            (target / "fixtures/registration.txt").write_text("Changed policy")
            with self.assertRaisesRegex(ValueError, "manifest update"):
                validate(target)

    def test_unmanifested_fixture_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "corpus"
            shutil.copytree(SCENARIO, target)
            (target / "fixtures/unreviewed.txt").write_text("Unreviewed")
            with self.assertRaisesRegex(ValueError, "inventory"):
                validate(target)


if __name__ == "__main__":
    unittest.main()
