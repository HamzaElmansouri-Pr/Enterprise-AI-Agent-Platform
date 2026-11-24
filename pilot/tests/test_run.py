import tempfile
import unittest
from unittest.mock import patch

from pilot.run import main


class OwnedEnvironmentTest(unittest.TestCase):
    def test_existing_directory_is_rejected_before_any_provision_or_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            arguments = [
                "pilot.run",
                "--directory",
                directory,
                "--model-dir",
                directory,
                "--playwright-package",
                directory + "/package.json",
                "--output",
                directory + "/evidence",
            ]
            with (
                patch("sys.argv", arguments),
                patch("pilot.run.provision") as provision,
                patch("pilot.run.Pilot") as pilot,
            ):
                with self.assertRaisesRegex(ValueError, "new external"):
                    main()
                provision.assert_not_called()
                pilot.assert_not_called()
