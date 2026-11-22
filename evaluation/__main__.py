"""Run from repository root: python -m evaluation --help."""

import argparse
import json
import sys
from pathlib import Path

from evaluation.dataset import load
from evaluation.reports import apply_review, compare, read, review_template, write
from evaluation.runner import execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("--dataset", required=True)
    run = commands.add_parser("run")
    run.add_argument("--dataset", required=True)
    run.add_argument(
        "--suite", choices=["all", "retrieval", "security", "generation"], required=True
    )
    run.add_argument("--mode", choices=["offline", "hosted"], default="offline")
    run.add_argument("--model-dir", required=True)
    run.add_argument("--output", required=True)
    run.add_argument("--cases")
    run.add_argument("--provider")
    run.add_argument("--model")
    run.add_argument("--max-cases", type=int)
    run.add_argument("--max-attempts", type=int)
    run.add_argument("--max-budget", type=float)
    for name in ("report", "review-template"):
        command = commands.add_parser(name)
        command.add_argument("--run", required=True, help="Path to results.json")
        command.add_argument("--output", required=True)
    review = commands.add_parser("review")
    review.add_argument("--run", required=True)
    review.add_argument("--form", required=True)
    review.add_argument("--output", required=True)
    comparison = commands.add_parser("compare")
    comparison.add_argument("--baseline", required=True)
    comparison.add_argument("--candidate", required=True)
    comparison.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if args.command == "validate":
            dataset = load(args.dataset)
            print(
                json.dumps(
                    {
                        "valid": True,
                        "hash": dataset["hash"],
                        "counts": dataset["manifest"]["counts"],
                    }
                )
            )
        elif args.command == "run":
            report = execute(args)
            print(
                json.dumps(
                    {
                        "experimentId": report["experimentId"],
                        "status": report["status"],
                        "cases": len(report["cases"]),
                        "paidCalls": report["paidCalls"],
                    }
                )
            )
            return 0 if report["status"] == "COMPLETE" else 1
        elif args.command == "report":
            write(read(args.run), args.output)
        elif args.command == "review-template":
            Path(args.output).write_text(
                json.dumps(review_template(read(args.run)), indent=2) + "\n"
            )
        elif args.command == "review":
            write(apply_review(read(args.run), read(args.form)), args.output)
        elif args.command == "compare":
            result = compare(read(args.baseline), read(args.candidate))
            Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
            print(
                json.dumps(
                    {
                        "compatible": result["compatible"],
                        "securityRegression": result["securityRegression"],
                    }
                )
            )
            return 0 if result["compatible"] and not result["securityRegression"] else 1
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(
            "Evaluation rejected: "
            + type(error).__name__
            + "; check documented inputs and controls.",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
