"""Run the prebuilt APK pair; convert AndroidJUnitRunner's raw protocol to fail-closed JUnit XML."""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from smoke_artifacts import NAMES, RUNNER, verify


def parse_results(lines, returncode=0, elapsed=0):
    """Protocol constants: androidx.test.internal.runner.listener.InstrumentationResultPrinter.

    A success string or adb exit 0 is insufficient: require matching start/end identities, stable test
    counts, one terminal RESULT_OK (-1), and no runner error. Keep full diagnostics in the raw log.
    """
    suite = ET.Element("testsuite", name="AndroidJUnitRunner", time=f"{elapsed:.3f}")
    packet = {}
    last_key = None
    active = None
    declared = None
    terminal = []
    seen = set()
    issues = []
    issue_count = failures = errors = skipped = 0

    def issue(message):
        nonlocal issue_count
        issue_count += 1
        if len(issues) < 20:
            issues.append(message)

    def status(code):
        nonlocal active, declared, failures, errors, skipped
        try:
            count = int(packet["numtests"])
            current = int(packet["current"])
            key = (packet["class"], packet["test"])
            if count <= 0 or not 1 <= current <= count or not all(key) or packet.get("id") != "AndroidJUnitRunner":
                raise ValueError("invalid identity/count")
            if declared is None:
                declared = count
            if count != declared:
                raise ValueError("test count changed")
        except (KeyError, ValueError) as error:
            issue(f"Invalid status bundle: {error}")
            return
        if code == 1:
            if active is not None or key in seen or current != len(seen) + 1:
                issue(f"Duplicate, overlapping or out-of-order start: {key}")
            active = (key, current)
            return
        if code not in (0, -1, -2, -3, -4):
            issue(f"Unknown test status: {code}")
            return
        if active != (key, current) or key in seen:
            issue(f"Unmatched or duplicate completion: {key}")
        active = None
        seen.add(key)
        case = ET.SubElement(suite, "testcase", classname=key[0], name=key[1])
        if code in (-1, -2):
            tag = "error" if code == -1 else "failure"
            ET.SubElement(case, tag).text = packet.get("stack", packet.get("stream", "Test failed"))[:4000]
            errors += code == -1
            failures += code == -2
        elif code in (-3, -4):
            ET.SubElement(case, "skipped").text = packet.get("stack", "Ignored or assumption failed")[:4000]
            skipped += 1

    for raw in lines:
        line = raw.rstrip("\r\n")
        if line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            if terminal:
                issue("Test status after terminal result")
            try:
                status(int(line.partition(": ")[2]))
            except ValueError:
                issue("Invalid status code")
            packet.clear()
            last_key = None
        elif line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if not separator or key in packet:
                issue("Malformed or duplicate status field")
            packet[key] = value
            last_key = key
        elif line.startswith("INSTRUMENTATION_CODE: "):
            try:
                terminal.append(int(line.partition(": ")[2]))
            except ValueError:
                issue("Invalid terminal code")
            last_key = None
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            if line.startswith("INSTRUMENTATION_RESULT: shortMsg="):
                issue(line)
            last_key = None
        elif line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:")):
            issue(line)
            last_key = None
        elif last_key in ("stack", "stream") and len(packet[last_key]) < 4000:
            packet[last_key] = (packet[last_key] + "\n" + line)[:4000]
    if packet or active is not None:
        issue("Truncated status bundle or unfinished test")
    if terminal != [-1]:
        issue(f"Expected exactly one successful terminal code, received {terminal}")
    if declared is None or declared != len(seen):
        issue(f"Incomplete test run: declared {declared}, completed {len(seen)}")
    if returncode:
        issue(f"adb runner exited with {returncode}")
    suite.set("tests", str(len(suite.findall("testcase"))))
    suite.set("failures", str(failures))
    suite.set("errors", str(errors + issue_count))
    suite.set("skipped", str(skipped))
    if issues:
        ET.SubElement(suite, "system-err").text = "\n".join(issues)
    return suite


def run(args):
    args.output.mkdir(parents=True, exist_ok=True)
    report = args.output / "TEST-instrumentation.xml"
    started = time.monotonic()
    suite = ET.Element("testsuite", name="AndroidJUnitRunner", tests="0", failures="0", errors="1")
    try:
        manifest = verify(args.bundle, args.revision)
        def adb(*command):
            return subprocess.check_output([args.adb, *command], stderr=subprocess.STDOUT, timeout=120,
                                           text=True, encoding="utf-8", errors="replace").strip()
        api = int(adb("shell", "getprop", "ro.build.version.sdk"))
        if api != args.api:
            raise ValueError(f"Expected API {args.api}, connected device is API {api}")
        for name in NAMES.values():
            print(adb("install", "-r", str(args.bundle / name)), flush=True)
        instrumentation = f"{manifest['test_application_id']}/{RUNNER}"
        expected = f"instrumentation:{instrumentation} (target={manifest['application_id']})"
        if expected not in adb("shell", "pm", "list", "instrumentation").splitlines():
            raise ValueError("Installed test runner does not target the expected application")
        command = [args.adb, "shell", "am", "instrument", "-w", "-r"]
        if args.test_class:
            command += ["-e", "class", args.test_class]
        command.append(instrumentation)
        log = args.output / "instrumentation.txt"
        with log.open("w", encoding="utf-8") as output:
            try:
                completed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=args.timeout)
                returncode = completed.returncode
            except subprocess.TimeoutExpired:
                returncode = 124
                # End only the verification application after a hard timeout; never replay failed tests.
                for package in (manifest["test_application_id"], manifest["application_id"]):
                    subprocess.run([args.adb, "shell", "am", "force-stop", package], timeout=15, check=False)
        with log.open(encoding="utf-8", errors="replace") as lines:
            suite = parse_results(lines, returncode, time.monotonic() - started)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        detail = str(error)
        output = getattr(error, "output", None)
        if output:
            detail += "\n" + (output.decode("utf-8", errors="replace") if isinstance(output, bytes) else output)
        ET.SubElement(suite, "system-err").text = detail[:4000]
        print(f"FAIL device setup/run: {detail[:4000]}", flush=True)
    finally:
        ET.ElementTree(suite).write(report, encoding="utf-8", xml_declaration=True)
    print(f"Device report: {report} tests={suite.get('tests')} failures={suite.get('failures')} errors={suite.get('errors')}")
    return int(suite.get("errors", "1")) == 0 and int(suite.get("failures", "1")) == 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--test-class", help="Local diagnosis only; CI runs the complete package without a filter")
    arguments = parser.parse_args()
    if arguments.timeout <= 0:
        parser.error("timeout must be positive")
    if arguments.test_class and not re.fullmatch(r"[A-Za-z0-9_.$#,\[\]-]+", arguments.test_class):
        parser.error("invalid test class filter")
    raise SystemExit(0 if run(arguments) else 1)
