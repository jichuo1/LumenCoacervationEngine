"""tools/central_bundle.py 的主机测试：不需要 Android SDK；签名用例需要本机有 gpg，否则跳过。"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import central_bundle as cb  # noqa: E402

GROUP = "io.github.example"
TAG = "9.9.9"

GRADLE_POM = """<?xml version="1.0" encoding="UTF-8"?>
<!-- This module was also published with a richer model, Gradle metadata,  -->
<!-- which should be used instead. Do not delete the following line which  -->
<!-- is to indicate to Gradle or any Gradle module metadata file consumer  -->
<!-- that they should prefer consuming it instead. -->
<!-- do_not_remove: published-with-gradle-metadata -->
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId>
  <artifactId>{module}</artifactId>
  <version>{tag}</version>
  <packaging>aar</packaging>
  <name>{module}</name>
  <description>Fixture module {module}.</description>
  <licenses>
    <license>
      <name>The Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
{dependencies}</project>
"""

DEPENDENCIES = """  <dependencies>
    <dependency>
      <groupId>{dep_group}</groupId>
      <artifactId>lumen-engine</artifactId>
      <version>{tag}</version>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>androidx.core</groupId>
      <artifactId>core-ktx</artifactId>
      <version>1.0.0</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
"""


def write_zip(path, entries):
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)


def gradle_checksums(path):
    for algorithm, suffix in cb.CHECKSUMS:
        path.with_name(path.name + suffix).write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())


def make_source(root, group=GROUP, tag=TAG, dep_group=None, skip_module=None, extra_file=None):
    """模拟 `publishAllPublicationsToProjectLocalRepository -Plumen.group=...` 之后的源码目录。"""
    (root / "gradle.properties").write_text(f"lumen.group=com.lumen.coacervation.engine\nlumen.version={tag}\n")
    for module in cb.MODULES:
        if module == skip_module:
            continue
        directory = root / "build" / "repo" / Path(*group.split(".")) / module / tag
        directory.mkdir(parents=True)
        base = directory / f"{module}-{tag}"
        write_zip(Path(str(base) + ".aar"), {"classes.jar": b"classes", "AndroidManifest.xml": b"<manifest/>"})
        write_zip(Path(str(base) + "-sources.jar"), {f"com/example/{module}.kt": b"class A"})
        Path(str(base) + ".module").write_text('{"formatVersion": "1.1"}')
        dependencies = DEPENDENCIES.format(dep_group=dep_group or group, tag=tag) if module != "lumen-engine" else ""
        Path(str(base) + ".pom").write_text(GRADLE_POM.format(group=group, module=module, tag=tag, dependencies=dependencies))
        for suffix in (".aar", "-sources.jar", ".module", ".pom"):
            gradle_checksums(Path(str(base) + suffix))
        if extra_file:
            (directory / extra_file).write_text("unexpected")
        # Gradle 还会在模块目录写 maven-metadata.xml，bundle 里不能有它。
        (directory.parent / "maven-metadata.xml").write_text("<metadata/>")


class CentralBundleTest(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.scratch, ignore_errors=True)
        self.source = self.scratch / "source"
        self.source.mkdir()
        self.output = self.scratch / "out"

    def package(self, **kwargs):
        make_source(self.source, **kwargs)
        return cb.package(self.source, GROUP, TAG, self.output)

    def test_package_produces_the_exact_central_layout(self):
        bundle = self.package()
        self.assertEqual(f"lumen-central-{TAG}-unsigned.zip", bundle.name)
        with zipfile.ZipFile(bundle) as archive:
            names = set(archive.namelist())
        self.assertEqual(cb.expected_names(GROUP, TAG, signed=False), names)
        self.assertTrue(all(name.startswith("io/github/example/") for name in names))
        self.assertFalse(any("maven-metadata" in name for name in names))
        self.assertEqual(len(cb.MODULES) * 5 * 5, len(names))
        cb.verify(bundle, GROUP, TAG)

    def test_pom_gains_central_metadata_and_keeps_the_gradle_marker(self):
        bundle = self.package()
        pom = f"io/github/example/lumen-motion/{TAG}/lumen-motion-{TAG}.pom"
        with zipfile.ZipFile(bundle) as archive:
            text = archive.read(pom).decode("utf-8")
        self.assertIn("do_not_remove: published-with-gradle-metadata", text)
        self.assertIn(f"<tag>{TAG}</tag>", text)
        self.assertIn("<url>https://github.com/jichuo1/LumenCoacervationEngine</url>", text)
        self.assertIn("<developer>", text)
        self.assertTrue(text.index("</description>") < text.index("<url>https://github.com") < text.index("<licenses>"))
        self.assertIn("androidx.core", text)

    def test_javadoc_is_a_deterministic_placeholder_jar(self):
        first = self.package()
        second_output = self.scratch / "again"
        second = cb.package(self.source, GROUP, TAG, second_output)
        self.assertEqual(hashlib.sha256(first.read_bytes()).hexdigest(), hashlib.sha256(second.read_bytes()).hexdigest())
        with zipfile.ZipFile(first) as archive:
            inner = archive.read(f"io/github/example/lumen-engine/{TAG}/lumen-engine-{TAG}-javadoc.jar")
        path = self.scratch / "javadoc.jar"
        path.write_bytes(inner)
        with zipfile.ZipFile(path) as javadoc:
            self.assertIn("README.txt", javadoc.namelist())

    def test_default_engine_group_is_refused(self):
        make_source(self.source, group=cb.DEFAULT_GROUP)
        with self.assertRaises(cb.BundleError):
            cb.package(self.source, cb.DEFAULT_GROUP, TAG, self.output)

    def test_dependency_on_another_group_is_refused(self):
        with self.assertRaises(cb.BundleError):
            self.package(dep_group=cb.DEFAULT_GROUP)

    def test_missing_module_is_refused(self):
        with self.assertRaises(cb.BundleError):
            self.package(skip_module="lumen-compose")

    def test_unexpected_publication_file_is_refused(self):
        with self.assertRaises(cb.BundleError):
            self.package(extra_file="surprise.txt")

    def test_tag_must_match_the_source_version(self):
        make_source(self.source)
        (self.source / "gradle.properties").write_text("lumen.version=1.0.0\n")
        with self.assertRaises(cb.BundleError):
            cb.package(self.source, GROUP, TAG, self.output)

    def test_verify_rejects_a_tampered_file_and_a_missing_checksum(self):
        bundle = self.package()
        with zipfile.ZipFile(bundle) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        target = f"io/github/example/lumen-engine/{TAG}/lumen-engine-{TAG}.module"
        tampered = dict(entries, **{target: b"changed"})
        path = self.scratch / "tampered.zip"
        write_zip(path, tampered)
        with self.assertRaises(cb.BundleError):
            cb.verify(path, GROUP, TAG)
        missing = {name: data for name, data in entries.items() if name != target + ".sha1"}
        path = self.scratch / "missing.zip"
        write_zip(path, missing)
        with self.assertRaises(cb.BundleError):
            cb.verify(path, GROUP, TAG)

    def test_verify_rejects_unsafe_and_unexpected_entries(self):
        bundle = self.package()
        with zipfile.ZipFile(bundle) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        for extra in ("../escape.txt", "io/github/example/notes.txt"):
            path = self.scratch / "extra.zip"
            write_zip(path, dict(entries, **{extra: b"x"}))
            with self.assertRaises(cb.BundleError):
                cb.verify(path, GROUP, TAG)

    def test_unsigned_bundle_is_not_accepted_as_signed(self):
        bundle = self.package()
        with self.assertRaises(cb.BundleError):
            cb.verify(bundle, GROUP, TAG, signed=True)

    def test_invalid_arguments(self):
        for group, tag in (("Io.Github", TAG), ("single", TAG), (GROUP, "1.0"), (GROUP, "1.0.0-SNAPSHOT")):
            with self.assertRaises(cb.BundleError):
                cb.check_arguments(group, tag)


@unittest.skipUnless(shutil.which("gpg"), "gpg is not installed")
class SigningTest(unittest.TestCase):
    def setUp(self):
        self.scratch = Path(tempfile.mkdtemp(prefix="cb"))
        self.addCleanup(shutil.rmtree, self.scratch, ignore_errors=True)
        home = self.scratch / "gnupg"
        home.mkdir(mode=0o700)
        patcher = mock.patch.dict(os.environ, {"GNUPGHOME": str(home)})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(lambda: subprocess.run(["gpgconf", "--kill", "gpg-agent"], env=dict(os.environ, GNUPGHOME=str(home)),
                                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        subprocess.run(["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "", "--quick-generate-key",
                        "Central Test <central-test@example.invalid>", "ed25519", "sign", "never"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        source = self.scratch / "source"
        source.mkdir()
        make_source(source)
        self.bundle = cb.package(source, GROUP, TAG, self.scratch / "out")

    def test_sign_roundtrip_and_detects_a_bad_signature(self):
        signed = self.scratch / "signed.zip"
        fingerprint = cb.sign(self.bundle, GROUP, TAG, "central-test@example.invalid", signed)
        self.assertEqual(40, len(fingerprint))
        cb.verify(signed, GROUP, TAG, signed=True, gpg="gpg")
        with zipfile.ZipFile(signed) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        self.assertEqual(len(cb.MODULES) * 5, len([n for n in entries if n.endswith(".asc")]))
        self.assertFalse(any(n.endswith(".asc.md5") or n.endswith(".asc.sha1") for n in entries))
        victim = f"io/github/example/lumen-engine/{TAG}/lumen-engine-{TAG}.pom"
        broken = dict(entries)
        broken[victim] = broken[victim] + b" "
        for algorithm, suffix in cb.CHECKSUMS:
            broken[victim + suffix] = hashlib.new(algorithm, broken[victim]).hexdigest().encode("ascii")
        path = self.scratch / "broken.zip"
        write_zip(path, broken)
        with self.assertRaises(cb.BundleError):
            cb.verify(path, GROUP, TAG, signed=True, gpg="gpg")

    def test_unknown_key_is_refused(self):
        with self.assertRaises(cb.BundleError):
            cb.sign(self.bundle, GROUP, TAG, "nobody@example.invalid", self.scratch / "signed.zip")


if __name__ == "__main__":
    unittest.main()
