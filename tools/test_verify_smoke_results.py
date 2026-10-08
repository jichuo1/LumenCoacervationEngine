import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / ".github/scripts/verify-smoke-results.py"
SPEC = importlib.util.spec_from_file_location("verify_smoke_results", SCRIPT)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class SmokeResultsTest(unittest.TestCase):
    def check(self, api=34, omit=None, problem=None, suite_errors="0", empty=False, malformed=False, stale=False):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            root = ET.Element("testsuite", errors=suite_errors)
            keys = {(owner, name) for owner, names in VERIFIER.REQUIRED.items() for name in names}
            if api >= 31:
                keys.add(VERIFIER.GPU_TEST)
            if api >= 33:
                keys.update(VERIFIER.ENHANCED_GPU_TESTS)
                keys.add(VERIFIER.P2_GPU_TEST)
            for owner, name in sorted(keys if not empty else set()):
                if (owner, name) == omit:
                    continue
                case = ET.SubElement(root, "testcase", classname=owner, name=name)
                if (owner, name) == VERIFIER.GPU_TEST and problem:
                    ET.SubElement(case, problem)
            path = directory / "TEST-result.xml"
            if malformed:
                path.write_text("<unfinished", encoding="utf-8")
            else:
                ET.ElementTree(root).write(path)
            with contextlib.redirect_stdout(io.StringIO()):
                return VERIFIER.verify(directory, api, time.time() + 60 if stale else 0)

    def test_complete_matrix_result_is_accepted(self):
        self.assertTrue(self.check())

    def test_min_sdk_does_not_require_gpu_test(self):
        self.assertTrue(self.check(api=27))

    def test_zero_tests_is_rejected(self):
        self.assertFalse(self.check(empty=True))

    def test_missing_gpu_assertion_is_rejected(self):
        self.assertFalse(self.check(omit=VERIFIER.GPU_TEST))
    def test_missing_new_pixel_test_is_rejected(self):
        self.assertFalse(self.check(omit=next(iter(VERIFIER.ENHANCED_GPU_TESTS))))
    def test_missing_p2_native_format_or_pixels_is_rejected(self):
        self.assertFalse(self.check(omit=VERIFIER.P2_GPU_TEST))
        self.assertFalse(self.check(omit=(VERIFIER.PREFIX+"P2IntegrationTest","riveLoadsWithoutImplicitPlaybackAndCanClose")))
    def test_api31_does_not_require_agsl_tests(self):
        self.assertTrue(self.check(api=31))

    def test_failed_or_skipped_required_test_is_rejected(self):
        for problem in ("failure", "error", "skipped"):
            with self.subTest(problem=problem):
                self.assertFalse(self.check(problem=problem))

    def test_runner_error_without_failed_case_is_rejected(self):
        self.assertFalse(self.check(suite_errors="1"))

    def test_unreadable_result_is_rejected(self):
        self.assertFalse(self.check(malformed=True))

    def test_report_from_a_previous_invocation_is_rejected(self):
        self.assertFalse(self.check(stale=True))

    def test_absent_reports_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(VERIFIER.verify(Path(temporary), 34))


if __name__ == "__main__":
    unittest.main()
