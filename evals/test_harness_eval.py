import json
import tempfile
import unittest
from pathlib import Path

from run_harness_eval import (
    CASE_SCHEMA_VERSION,
    MINIMUM_CASE_COUNT,
    build_maven_command,
    load_cases,
    DEFAULT_CASES,
)


def golden_case(index: int, **overrides):
    row = {
        "id": f"evidence_case_{index:03d}",
        "phase": "EVIDENCE",
        "category": "evidence_availability",
        "tags": ["evidence", f"variant-{index:03d}"],
        "fixture": {"variant": index},
        "expected": {
            "outcome": "PASS",
            "violations": [],
            "recovery_actions": [],
            "allows_recommendation": True,
        },
    }
    row.update(overrides)
    return row


class HarnessEvalTest(unittest.TestCase):

    def test_golden_set_schema_is_valid(self):
        cases = load_cases(DEFAULT_CASES)
        self.assertGreaterEqual(len(cases), MINIMUM_CASE_COUNT)
        self.assertEqual(
            {"PASS", "RECOVER", "DEGRADE", "BLOCK"},
            {case["expected"]["outcome"] for case in cases},
        )
        self.assertEqual(
            {"EVIDENCE", "REPORT"},
            {case["phase"] for case in cases},
        )

    def test_accepts_sixty_unique_v2_cases(self):
        cases = [golden_case(index) for index in range(MINIMUM_CASE_COUNT)]
        with self._case_file(cases) as path:
            loaded = load_cases(path)
        self.assertEqual(MINIMUM_CASE_COUNT, len(loaded))

    def test_rejects_fewer_than_sixty_cases(self):
        cases = [golden_case(index) for index in range(MINIMUM_CASE_COUNT - 1)]
        with self._case_file(cases) as path:
            with self.assertRaisesRegex(ValueError, "at least 60"):
                load_cases(path)

    def test_rejects_duplicate_ids(self):
        cases = [golden_case(index) for index in range(MINIMUM_CASE_COUNT)]
        cases[-1]["id"] = cases[0]["id"]
        with self._case_file(cases) as path:
            with self.assertRaisesRegex(ValueError, "duplicate harness case id"):
                load_cases(path)

    def test_rejects_duplicate_semantic_fixtures_even_with_different_ids(self):
        cases = [golden_case(index) for index in range(MINIMUM_CASE_COUNT)]
        cases[-1]["fixture"] = cases[0]["fixture"]
        with self._case_file(cases) as path:
            with self.assertRaisesRegex(
                ValueError, "duplicate harness semantic fixture"
            ):
                load_cases(path)

    def test_rejects_non_v2_shape_and_phase_typo(self):
        cases = [golden_case(index) for index in range(MINIMUM_CASE_COUNT)]
        cases[0]["unexpected"] = True
        cases[1]["phase"] = "EVIDNCE"
        with self._case_file(cases) as path:
            with self.assertRaisesRegex(ValueError, "unknown fields"):
                load_cases(path)

        cases[0].pop("unexpected")
        with self._case_file(cases) as path:
            with self.assertRaisesRegex(ValueError, "phase must be one of"):
                load_cases(path)

    def test_rejects_incomplete_expected_contract(self):
        cases = [golden_case(index) for index in range(MINIMUM_CASE_COUNT)]
        cases[0]["expected"].pop("recovery_actions")
        with self._case_file(cases) as path:
            with self.assertRaisesRegex(ValueError, "expected missing fields"):
                load_cases(path)

    def test_rejects_recommendation_permission_that_conflicts_with_outcome(self):
        cases = [golden_case(index) for index in range(MINIMUM_CASE_COUNT)]
        cases[0]["expected"]["allows_recommendation"] = False
        with self._case_file(cases) as path:
            with self.assertRaisesRegex(ValueError, "must agree"):
                load_cases(path)

    def test_java_command_targets_the_production_policy_golden_test(self):
        command = build_maven_command(
            Path("backend"),
            Path("cases.jsonl"),
            Path("result.json"),
            platform="nt",
        )
        self.assertTrue(command[0].endswith("mvnw.cmd"))
        self.assertIn("-Dtest=HarnessGoldenSetTest", command)
        self.assertTrue(any(item.startswith("-Dharness.cases=") for item in command))
        self.assertTrue(any(item.startswith("-Dharness.output=") for item in command))

    def test_case_schema_version_is_the_v2_contract(self):
        self.assertEqual("harness_golden_case_v2", CASE_SCHEMA_VERSION)

    def _case_file(self, cases):
        class CaseFile:
            def __enter__(inner_self):
                inner_self.directory = tempfile.TemporaryDirectory()
                path = Path(inner_self.directory.name) / "cases.jsonl"
                path.write_text(
                    "\n".join(
                        json.dumps(case, ensure_ascii=False) for case in cases
                    )
                    + "\n",
                    encoding="utf-8",
                )
                return path

            def __exit__(inner_self, exc_type, exc_value, traceback):
                inner_self.directory.cleanup()

        return CaseFile()


if __name__ == "__main__":
    unittest.main()
