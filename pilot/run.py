"""One-command disposable browser experiment. Recovery/evaluation run separately."""

import argparse
import os
from pathlib import Path

from deploy.scripts.pilot import ROOT, Pilot, checked
from pilot.dataset import validate
from pilot.environment import provision


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True)
    parser.add_argument("--model-dir", required=True)
    parser.add_argument("--port", type=int, default=18443)
    parser.add_argument("--playwright-package", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    validate()
    directory = Path(args.directory).resolve()
    if directory.exists() or directory.is_relative_to(ROOT):
        raise ValueError("Choose a new external experiment directory")
    pilot = None
    try:
        credentials = provision(directory, args.model_dir, args.port)
        pilot = Pilot(directory / "pilot.json")
        env = dict(
            os.environ,
            EAAP_PLAYWRIGHT_PACKAGE=str(Path(args.playwright_package).resolve()),
        )
        # Browser failure bodies may contain private form input; do not print them.
        checked(
            [
                "node",
                str(ROOT / "pilot/browser.mjs"),
                str(credentials),
                str(Path(args.output).resolve()),
            ],
            timeout=1800,
            env=env,
        )
        print(
            "Browser acceptance passed; inspect screenshots before publishing evidence."
        )
    finally:
        if pilot is None and (directory / "images.json").is_file():
            pilot = Pilot(directory / "pilot.json")
        if pilot is not None:
            pilot.compose(["down", "--remove-orphans"])
        # Retain generated operator files/volumes for explicit recovery analysis,
        # but remove browser credential input after this owned experiment finishes.
        if (directory / "browser-input.json").is_file():
            (directory / "browser-input.json").unlink()


if __name__ == "__main__":
    main()
