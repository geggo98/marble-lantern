#!/usr/bin/env python3
"""Tests that the written state of the docs agrees with the booked results. Run: python3 -I cpp/scripts/test_docs_state.py

README.md must not say any more that nothing ran on AWS, that nn_bench is a skeleton, that nvcc never read the CUDA code or that merge 3
is still to do (all four were settled by the runs of 2026-11-15); the plan must carry the load-limit amendment that the Mac result files rely on.
"""
import os
import unittest

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")


def text(*p):
    with open(os.path.join(root, *p), encoding="utf-8") as f:
        return f.read()


class DocsStateTest(unittest.TestCase):
    def test_readme_has_no_stale_statements(self):
        readme = text("cpp", "README.md")
        for stale in ("Nothing here ran against AWS", "is still the skeleton of WP0", "to do by the lead",
                      "nvcc has never read", "What stays unproven until the AWS gate", "`compute-sanitizer`** was never run"):
            self.assertNotIn(stale, readme)

    def test_readme_points_to_the_aws_result(self):
        readme = text("cpp", "README.md")
        self.assertIn("results/aws-t4-2026-11-15.txt", readme)
        self.assertIn("f12c5a0", readme)

    def test_plan_states_the_relaxed_load_limit(self):
        plan = text("docs", "cpp-comparison-plan.md")
        self.assertIn("Amendment 2 2026-11-15", plan)
        self.assertIn("load limit of 4.0", plan)

    def test_games_result_names_the_relaxed_load_rule(self):
        t = text("cpp", "results", "mac-classical-games-2026-11-15.txt")
        self.assertNotIn("Every check passed", t)


if __name__ == "__main__":
    unittest.main()
