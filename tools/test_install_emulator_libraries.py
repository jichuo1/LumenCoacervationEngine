import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[1]/'.github/scripts/install-emulator-libraries.sh'
BASH=shutil.which('bash')
if BASH is None and os.name=='nt':
    candidate=Path(os.environ.get('ProgramFiles','C:/Program Files'))/'Git/bin/bash.exe'
    if candidate.is_file():BASH=str(candidate)

class EmulatorLibrariesTest(unittest.TestCase):
    def run_case(self,present=False,codename='noble',failure=False):
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory);bin_dir=root/'bin';bin_dir.mkdir();log=root/'calls'
            scripts={'ldconfig':'#!/bin/bash\nif [ "$TEST_PRESENT" = 1 ]; then echo "libpulse.so.0 => /lib/libpulse.so.0"; fi\n',
                     'sudo':'#!/bin/bash\nprintf "%s\\n" "$*" >> "$TEST_CALLS"\nif [ "$TEST_FAILURE" = 1 ]; then exit 124; fi\n'}
            for name,text in scripts.items():
                target=bin_dir/name;target.write_text(text);target.chmod(0o755)
            release=root/'os-release';release.write_text('VERSION_CODENAME='+codename+'\n')
            env=os.environ.copy();env.update(PATH=str(bin_dir)+os.pathsep+env['PATH'],TEST_PRESENT=str(int(present)),TEST_FAILURE=str(int(failure)),TEST_CALLS=str(log),LUMEN_CI_OS_RELEASE=str(release),LUMEN_CI_APT_DIRECTORY=str(root/'apt'))
            self.assertIsNotNone(BASH,'Bash is required for CI script regression')
            result=subprocess.run([BASH,SCRIPT.as_posix()],env=env,capture_output=True,text=True,timeout=10)
            return result.returncode,log.read_text()if log.exists()else '',(root/'apt/ubuntu.sources').read_text()if(root/'apt/ubuntu.sources').exists()else ''
    def test_existing_library_avoids_network(self):
        code,calls,_=self.run_case(present=True);self.assertEqual(0,code);self.assertEqual('',calls)
    def test_scoped_signed_https_sources_and_bounded_commands(self):
        code,calls,source=self.run_case();self.assertEqual(0,code);self.assertEqual(2,len(calls.splitlines()))
        for option in ('timeout 180s','Acquire::Retries=2','Acquire::https::Timeout=30','Dir::Etc::sourceparts=-'):self.assertIn(option,calls)
        self.assertIn('https://archive.ubuntu.com/ubuntu',source);self.assertIn('https://security.ubuntu.com/ubuntu',source)
        self.assertIn('Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg',source);self.assertNotIn('azure',source)
    def test_failed_update_stops_before_install(self):
        code,calls,_=self.run_case(failure=True);self.assertEqual(124,code);self.assertEqual(1,len(calls.splitlines()));self.assertNotIn(' install ',calls)
    def test_invalid_codename_is_rejected(self):
        code,calls,_=self.run_case(codename='noble; bad');self.assertNotEqual(0,code);self.assertEqual('',calls)

if __name__=='__main__':unittest.main()
