import contextlib
import io
import json
from pathlib import Path
from types import SimpleNamespace
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPTS = Path(__file__).resolve().parents[1] / ".github/scripts"
sys.path.insert(0, str(SCRIPTS))
import smoke_artifacts as artifacts
import run_device_tests as runner


def packet(code, name="works", current=1, count=1):
    return (f"INSTRUMENTATION_STATUS: class=example.Test\n"
            f"INSTRUMENTATION_STATUS: test={name}\n"
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
            f"INSTRUMENTATION_STATUS: current={current}\n"
            f"INSTRUMENTATION_STATUS: numtests={count}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


class RawProtocolTest(unittest.TestCase):
    def parse(self, text, code=0):
        return runner.parse_results(io.StringIO(text), code)

    def good(self):
        return packet(1) + packet(0) + "INSTRUMENTATION_CODE: -1\n"

    def accepted(self, text, code=0):
        root = self.parse(text, code)
        return root.get("errors") == "0" and root.get("failures") == "0"

    def test_real_start_end_and_terminal_are_required(self):
        self.assertTrue(self.accepted(self.good()))
        self.assertFalse(self.accepted("OK (1 test)\nINSTRUMENTATION_CODE: -1\n"))

    def test_crash_after_passes_and_adb_failure_cannot_be_green(self):
        for tail in ("", "INSTRUMENTATION_CODE: 0\n", "INSTRUMENTATION_RESULT: shortMsg=Process crashed\nINSTRUMENTATION_CODE: -1\n"):
            with self.subTest(tail=tail):
                self.assertFalse(self.accepted(packet(1) + packet(0) + tail))
        self.assertFalse(self.accepted(self.good(), code=1))
        self.assertFalse(self.accepted(self.good(), code=124))

    def test_failures_errors_ignored_and_assumptions_keep_their_semantics(self):
        for status, tag in ((-1, "error"), (-2, "failure"), (-3, "skipped"), (-4, "skipped")):
            root = self.parse(packet(1) + packet(status) + "INSTRUMENTATION_CODE: -1\n")
            self.assertIsNotNone(root.find(f"testcase/{tag}"))
            if status in (-1, -2):
                self.assertFalse(self.accepted(packet(1) + packet(status) + "INSTRUMENTATION_CODE: -1\n"))

    def test_truncation_count_change_and_missing_completion_are_rejected(self):
        for text in (packet(1), packet(1, count=2) + packet(0), packet(0),
                     packet(1) + "INSTRUMENTATION_STATUS: class=example.Test\n",
                     packet(1, count=2) + packet(0, count=2) + "INSTRUMENTATION_CODE: -1\n"):
            with self.subTest(text=text):
                self.assertFalse(self.accepted(text))

    def test_duplicate_or_mismatched_identity_and_terminal_are_rejected(self):
        for text in (packet(1) + packet(0, name="other") + "INSTRUMENTATION_CODE: -1\n",
                     self.good() + self.good(), packet(1) + packet(1) + packet(0) + "INSTRUMENTATION_CODE: -1\n",
                     "INSTRUMENTATION_CODE: -1\n" + packet(1) + packet(0)):
            self.assertFalse(self.accepted(text))

    def test_malformed_numeric_fields_and_unknown_status_fail_closed(self):
        for text in (self.good().replace("current=1", "current=invalid"),
                     self.good().replace("numtests=1", "numtests=0"),
                     packet(1) + packet(8) + "INSTRUMENTATION_CODE: -1\n",
                     self.good().replace("CODE: 0", "CODE: invalid")):
            self.assertFalse(self.accepted(text))

    def test_failure_details_survive_multiline_protocol(self):
        failure = packet(-2).replace("INSTRUMENTATION_STATUS_CODE", "INSTRUMENTATION_STATUS: stack=AssertionError\n    at test.Method\nINSTRUMENTATION_STATUS_CODE")
        root = self.parse(packet(1) + failure + "INSTRUMENTATION_CODE: -1\n")
        self.assertIn("at test.Method", root.find("testcase/failure").text)


class ArtifactTest(unittest.TestCase):
    revision = "a" * 40

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = self.metadata("app", "example.app")
        self.test = self.metadata("test", "example.app.test")
        self.bundle = self.root / "bundle"
        artifacts.create(self.bundle, self.revision, self.app, self.test)

    def metadata(self, folder, app_id):
        directory = self.root / folder
        directory.mkdir()
        (directory / "output.apk").write_bytes(b"apk content " + app_id.encode())
        path = directory / "output-metadata.json"
        path.write_text(json.dumps({"variantName": "releaseAndroidTest" if folder == "test" else "release", "applicationId": app_id,
                                   "elements": [{"outputFile": "output.apk"}]}), encoding="utf-8")
        return path

    def manifest(self, change):
        path = self.bundle / "manifest.json"
        data = json.loads(path.read_text())
        change(data)
        path.write_text(json.dumps(data))

    def test_one_exact_apk_pair_is_revision_and_hash_bound(self):
        result = artifacts.verify(self.bundle, self.revision)
        self.assertEqual("example.app.test", result["test_application_id"])
        with self.assertRaises(ValueError):
            artifacts.verify(self.bundle, "b" * 40)
        (self.bundle / "sample.apk").write_bytes(b"changed")
        with self.assertRaises(ValueError):
            artifacts.verify(self.bundle, self.revision)

    def test_path_traversal_unexpected_runner_and_package_mismatch_are_rejected(self):
        for field, value in (("runner", "evil; command"), ("application_id", "bad; command"),
                             ("test_application_id", "other.test"), ("schema", 2)):
            artifacts.create(self.bundle, self.revision, self.app, self.test)
            self.manifest(lambda data: data.update({field: value}))
            with self.subTest(field=field), self.assertRaises(ValueError):
                artifacts.verify(self.bundle, self.revision)
        artifacts.create(self.bundle, self.revision, self.app, self.test)
        self.manifest(lambda data: data["apks"]["app"].update(file="../outside.apk"))
        with self.assertRaises(ValueError):
            artifacts.verify(self.bundle, self.revision)

    def test_missing_apk_and_incomplete_manifest_fail_closed(self):
        (self.bundle / "sample-tests.apk").unlink()
        with self.assertRaises((ValueError, OSError)):
            artifacts.verify(self.bundle, self.revision)
        (self.bundle / "manifest.json").write_text("{", encoding="utf-8")
        with self.assertRaises(ValueError):
            artifacts.verify(self.bundle, self.revision)


class DeviceExecutionTest(unittest.TestCase):
    revision = ArtifactTest.revision
    setUp = ArtifactTest.setUp
    metadata = ArtifactTest.metadata

    def arguments(self):
        return SimpleNamespace(output=self.root / "results", bundle=self.bundle, revision=self.revision,
                               api=34, adb="adb", timeout=900, test_class=None)

    def test_api_or_install_failure_writes_a_fresh_error_report(self):
        for responses in (["27"], ["34", subprocess.CalledProcessError(1, "adb install", output="INSTALL_FAILED_UPDATE_INCOMPATIBLE")]):
            with self.subTest(responses=responses), patch.object(runner.subprocess, "check_output", side_effect=responses), \
                    contextlib.redirect_stdout(io.StringIO()):
                args = self.arguments()
                self.assertFalse(runner.run(args))
                suite = ET.parse(args.output / "TEST-instrumentation.xml").getroot()
                self.assertEqual("1", suite.get("errors"))
                self.assertEqual("0", suite.get("tests"))
                if len(responses) > 1:
                    self.assertIn("INSTALL_FAILED_UPDATE_INCOMPATIBLE", suite.find("system-err").text)

    def test_no_class_filter_is_added_to_the_ci_execution(self):
        replies = ["34", "Success", "Success",
                   f"instrumentation:example.app.test/{artifacts.RUNNER} (target=example.app)"]
        def complete(command, **kwargs):
            self.assertEqual(["adb", "shell", "am", "instrument", "-w", "-r",
                              f"example.app.test/{artifacts.RUNNER}"], command)
            kwargs["stdout"].write(packet(1) + packet(0) + "INSTRUMENTATION_CODE: -1\n")
            return SimpleNamespace(returncode=0)
        with patch.object(runner.subprocess, "check_output", side_effect=replies), \
                patch.object(runner.subprocess, "run", side_effect=complete), contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(runner.run(self.arguments()))


if __name__ == "__main__":
    unittest.main()
