"""Exercise CI emulator installation retry behavior without SDK downloads."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / ".github" / "scripts" / "prepare-emulator.sh"
BASH = shutil.which("bash")
if BASH is None and os.name == "nt":
    candidate = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
    if candidate.is_file():
        BASH = str(candidate)


class PrepareEmulatorTest(unittest.TestCase):
    def run_scenario(self, scenario: str) -> subprocess.CompletedProcess[str]:
        self.assertIsNotNone(BASH, "Bash is required for the CI script regression")
        command = f'''source "{SCRIPT.as_posix()}"
attempts=0
sleep() {{ echo "BACKOFF $1"; }}
{scenario}
prepare_emulator
status=$?
echo "ATTEMPTS $attempts"
exit "$status"
'''
        return subprocess.run([BASH, "-c", command], capture_output=True, text=True, timeout=10)

    def test_success_stops_after_one_attempt(self) -> None:
        result = self.run_scenario('''
sdkmanager() { attempts=$((attempts + 1)); echo "SDK $*"; return 0; }
verify_emulator() { echo VERIFIED; return 0; }
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("SDK --install emulator --channel=0", result.stdout)
        self.assertIn("VERIFIED", result.stdout)
        self.assertIn("ATTEMPTS 1", result.stdout)
        self.assertNotIn("BACKOFF", result.stdout)

    def test_download_failure_recovers_on_third_attempt(self) -> None:
        result = self.run_scenario('''
sdkmanager() { attempts=$((attempts + 1)); [ "$attempts" -ge 3 ]; }
verify_emulator() { return 0; }
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("ATTEMPTS 3", result.stdout)
        self.assertIn("BACKOFF 5", result.stdout)
        self.assertIn("BACKOFF 10", result.stdout)
        self.assertNotIn("::error::", result.stdout)

    def test_persistent_failure_keeps_nonzero_exit_and_never_verifies(self) -> None:
        result = self.run_scenario('''
sdkmanager() { attempts=$((attempts + 1)); return 7; }
verify_emulator() { echo UNEXPECTED_VERIFICATION; return 0; }
''')
        self.assertEqual(7, result.returncode)
        self.assertIn("ATTEMPTS 3", result.stdout)
        self.assertNotIn("UNEXPECTED_VERIFICATION", result.stdout)
        self.assertEqual(2, result.stdout.count("BACKOFF"))
        self.assertIn("::error::", result.stdout)

    def test_broken_installed_binary_is_not_accepted(self) -> None:
        result = self.run_scenario('''
sdkmanager() { attempts=$((attempts + 1)); return 0; }
verify_emulator() { return 9; }
''')
        self.assertEqual(9, result.returncode)
        self.assertIn("ATTEMPTS 3", result.stdout)


if __name__ == "__main__":
    unittest.main()
