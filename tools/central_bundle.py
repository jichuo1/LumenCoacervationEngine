#!/usr/bin/env python3
"""Maven Central（Central Portal）上传包：打包、校验、本机签名。

- package / verify 由 CI 的 central-bundle.yml 运行：对不可变的发布 tag 以 Central 命名空间构建九个库模块，
  补齐 Central 要求的 POM 元数据与 javadoc 占位包，重算校验和，产出未签名的 bundle.zip。
- sign 在维护者本机运行：用本机 gpg 给每个文件生成 .asc，私钥与口令不进仓库也不进 CI。
见 docs/CENTRAL_PUBLISHING.md。只用标准库，兼容 Python 3.9。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

REPO_URL = "https://github.com/jichuo1/LumenCoacervationEngine"
DEVELOPER_ID = "jichuo1"
DEVELOPER_URL = "https://github.com/jichuo1"
MODULES = (
    "lumen-engine", "lumen-motion", "lumen-controls", "lumen-compose", "lumen-effects",
    "lumen-assets", "lumen-assets-lottie", "lumen-assets-pag", "lumen-assets-rive",
)
DEFAULT_GROUP = "com.lumen.coacervation.engine"
GROUP_PATTERN = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
TAG_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
POM_NAMESPACE = "{http://maven.apache.org/POM/4.0.0}"
# Central 要求 md5 与 sha1；sha256 / sha512 可选，一并写出。.asc 与校验和文件本身不需要校验和。
CHECKSUMS = (("md5", ".md5"), ("sha1", ".sha1"), ("sha256", ".sha256"), ("sha512", ".sha512"))
PAYLOAD_SUFFIXES = (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar")
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)


class BundleError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise BundleError(message)


def digest(path, algorithm):
    hasher = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            hasher.update(block)
    return hasher.hexdigest()


def write_checksums(path):
    for algorithm, suffix in CHECKSUMS:
        path.with_name(path.name + suffix).write_text(digest(path, algorithm))


def payload_names(group, module, tag):
    base = "/".join(group.split(".") + [module, tag, f"{module}-{tag}"])
    return [base + suffix for suffix in PAYLOAD_SUFFIXES]


def expected_names(group, tag, signed):
    names = set()
    for module in MODULES:
        for name in payload_names(group, module, tag):
            names.add(name)
            names.update(name + suffix for _, suffix in CHECKSUMS)
            if signed:
                names.add(name + ".asc")
    return names


def check_arguments(group, tag):
    require(GROUP_PATTERN.fullmatch(group) is not None, f"Invalid groupId: {group}")
    require(group != DEFAULT_GROUP, "The default engine groupId cannot be verified on Maven Central; use a verified namespace")
    require(TAG_PATTERN.fullmatch(tag) is not None, f"Invalid version: {tag}")


def check_source(source, tag):
    versions = re.findall(r"^lumen\.version=(.+)$", (source / "gradle.properties").read_text(), re.M)
    require(versions == [tag], "Tag must match source lumen.version")


# ---- POM -------------------------------------------------------------------------------------

def central_pom(raw, group, module, tag):
    """给 Gradle 生成的 POM 补上 url / developers / scm，其余逐字保留。

    不用 XML 序列化：Gradle 在 POM 开头留了 `published-with-gradle-metadata` 标记注释，
    Gradle 使用者靠它优先读取 .module；序列化会丢掉注释。
    """
    text = raw.decode("utf-8")
    newline = "\r\n" if "\r\n" in text else "\n"
    require(text.count("</description>") == 1 and text.count("</licenses>") == 1, f"{module}: unexpected POM layout")
    existing = ET.fromstring(raw)
    require(all(existing.find(POM_NAMESPACE + name) is None for name in ("url", "developers", "scm")),
            f"{module}: POM already declares url, developers or scm")
    url = f"  <url>{REPO_URL}</url>"
    people = newline.join([
        "  <developers>",
        "    <developer>",
        f"      <id>{DEVELOPER_ID}</id>",
        f"      <name>{DEVELOPER_ID}</name>",
        f"      <url>{DEVELOPER_URL}</url>",
        "    </developer>",
        "  </developers>",
        "  <scm>",
        f"    <connection>scm:git:{REPO_URL}.git</connection>",
        f"    <developerConnection>scm:git:ssh://git@github.com/jichuo1/LumenCoacervationEngine.git</developerConnection>",
        f"    <url>{REPO_URL}</url>",
        f"    <tag>{tag}</tag>",
        "  </scm>",
    ])
    text = text.replace("</description>", "</description>" + newline + url, 1)
    text = text.replace("</licenses>", "</licenses>" + newline + people, 1)
    check_pom(text.encode("utf-8"), group, module, tag)
    return text.encode("utf-8")


def check_pom(raw, group, module, tag):
    root = ET.fromstring(raw)
    require(root.tag == POM_NAMESPACE + "project", f"{module}: not a Maven POM")

    def text(parent, name):
        node = parent.find(POM_NAMESPACE + name)
        return (node.text or "").strip() if node is not None else ""

    require(text(root, "groupId") == group, f"{module}: POM groupId is not {group}")
    require(text(root, "artifactId") == module, f"{module}: POM artifactId mismatch")
    require(text(root, "version") == tag, f"{module}: POM version mismatch")
    for name in ("name", "description", "url"):
        require(text(root, name) != "", f"{module}: POM {name} is missing")
    licenses = root.findall(f"{POM_NAMESPACE}licenses/{POM_NAMESPACE}license")
    require(len(licenses) >= 1 and all(text(item, "name") and text(item, "url") for item in licenses), f"{module}: POM licenses incomplete")
    developers = root.findall(f"{POM_NAMESPACE}developers/{POM_NAMESPACE}developer")
    require(len(developers) >= 1 and all(text(item, "id") and text(item, "name") for item in developers), f"{module}: POM developers incomplete")
    scm = root.find(POM_NAMESPACE + "scm")
    require(scm is not None and all(text(scm, name) for name in ("connection", "developerConnection", "url")), f"{module}: POM scm incomplete")
    for dependency in root.findall(f"{POM_NAMESPACE}dependencies/{POM_NAMESPACE}dependency"):
        artifact = text(dependency, "artifactId")
        if artifact in MODULES:
            require(text(dependency, "groupId") == group and text(dependency, "version") == tag,
                    f"{module}: dependency on {artifact} does not use {group}:{tag}")
        require(text(dependency, "groupId") != DEFAULT_GROUP, f"{module}: POM still references {DEFAULT_GROUP}")


# ---- 打包 ------------------------------------------------------------------------------------

def javadoc_placeholder(path, module, tag):
    """Central 允许占位 javadoc 包；API 文档以仓库 docs/API.md 为准。"""
    note = (f"{module} {tag}\n\nThis library is written in Kotlin and does not ship generated Javadoc.\n"
            f"Public API reference: {REPO_URL}/blob/{tag}/docs/API.md\n")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in (("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"), ("README.txt", note)):
            info = zipfile.ZipInfo(name, FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)


def check_archive(path, kind, module):
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, f"{module}: invalid {kind} archive")
        names = set(archive.namelist())
        if kind == "aar":
            require({"classes.jar", "AndroidManifest.xml"} <= names, f"{module}: incomplete AAR")
        elif kind == "javadoc":
            require("README.txt" in names, f"{module}: javadoc jar is empty")
        else:
            require(any(name.endswith((".kt", ".java")) for name in names), f"{module}: sources jar has no sources")


def write_zip(source_dir, target):
    files = sorted(p for p in source_dir.rglob("*") if p.is_file())
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            info = zipfile.ZipInfo(path.relative_to(source_dir).as_posix(), FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())


def package(source, group, tag, output):
    check_arguments(group, tag)
    check_source(source, tag)
    group_dir = source / "build" / "repo" / Path(*group.split("."))
    require(group_dir.is_dir(), f"No publications for {group} under build/repo")
    require({p.name for p in group_dir.iterdir() if p.is_dir()} == set(MODULES), "Expected exactly the nine library modules")
    require(not output.exists() or not any(output.iterdir()), "Output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as scratch:
        stage = Path(scratch) / "stage"
        for module in MODULES:
            version_dir = group_dir / module / tag
            require(version_dir.is_dir(), f"{module}: no {tag} publication")
            published = {p.name for p in version_dir.iterdir() if p.is_file() and not p.name.endswith(tuple(s for _, s in CHECKSUMS))}
            base = f"{module}-{tag}"
            require(published == {base + suffix for suffix in (".aar", ".pom", ".module", "-sources.jar")},
                    f"{module}: unexpected publication files {sorted(published)}")
            target = stage / Path(*group.split(".")) / module / tag
            target.mkdir(parents=True)
            for suffix in (".aar", ".module", "-sources.jar"):
                shutil.copyfile(version_dir / (base + suffix), target / (base + suffix))
            (target / (base + ".pom")).write_bytes(central_pom((version_dir / (base + ".pom")).read_bytes(), group, module, tag))
            javadoc_placeholder(target / (base + "-javadoc.jar"), module, tag)
            check_archive(target / (base + ".aar"), "aar", module)
            check_archive(target / (base + "-sources.jar"), "sources", module)
            check_archive(target / (base + "-javadoc.jar"), "javadoc", module)
            for suffix in PAYLOAD_SUFFIXES:
                write_checksums(target / (base + suffix))
        bundle = output / f"lumen-central-{tag}-unsigned.zip"
        write_zip(stage, bundle)
    verify(bundle, group, tag, signed=False)
    manifest = {"schema": 1, "group": group, "version": tag, "bundle": bundle.name, "bundle_sha256": digest(bundle, "sha256"),
                "signed": False, "modules": list(MODULES)}
    (output / f"lumen-central-{tag}-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return bundle


# ---- 校验 ------------------------------------------------------------------------------------

def safe_name(name):
    return not (name.startswith("/") or "\\" in name or ".." in name.split("/") or name.endswith("/"))


def gpg_command(gpg):
    return [gpg, "--batch", "--no-tty"]


def verify(bundle, group, tag, signed=False, gpg=None):
    check_arguments(group, tag)
    with zipfile.ZipFile(bundle) as archive:
        require(archive.testzip() is None, "Invalid bundle archive")
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate entries in bundle")
        require(all(safe_name(name) for name in names), "Unsafe entry name in bundle")
        require(set(names) == expected_names(group, tag, signed), "Bundle inventory differs from the expected Maven layout")
        content = {name: archive.read(name) for name in names}
    for module in MODULES:
        for name in payload_names(group, module, tag):
            for algorithm, suffix in CHECKSUMS:
                require(content[name + suffix].decode("ascii").strip() == hashlib.new(algorithm, content[name]).hexdigest(),
                        f"Checksum mismatch: {name}{suffix}")
        pom = [n for n in payload_names(group, module, tag) if n.endswith(".pom")][0]
        check_pom(content[pom], group, module, tag)
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        for name, data in content.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        for module in MODULES:
            base = root / Path(*group.split(".")) / module / tag / f"{module}-{tag}"
            check_archive(Path(str(base) + ".aar"), "aar", module)
            check_archive(Path(str(base) + "-sources.jar"), "sources", module)
            check_archive(Path(str(base) + "-javadoc.jar"), "javadoc", module)
        if signed and gpg:
            for module in MODULES:
                for name in payload_names(group, module, tag):
                    result = subprocess.run(gpg_command(gpg) + ["--verify", str(root / (name + ".asc")), str(root / name)],
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    require(result.returncode == 0, f"Signature does not verify: {name}.asc")


# ---- 本机签名 --------------------------------------------------------------------------------

def key_fingerprint(gpg, key):
    result = subprocess.run(gpg_command(gpg) + ["--list-keys", "--with-colons", "--fingerprint", key],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    require(result.returncode == 0, f"gpg cannot find key {key}")
    prints = [line.split(":")[9] for line in result.stdout.splitlines() if line.startswith("fpr:")]
    require(bool(prints), f"gpg returned no fingerprint for {key}")
    return prints[0]


def sign(bundle, group, tag, key, output, gpg="gpg"):
    require(shutil.which(gpg) is not None, f"{gpg} not found; install GnuPG first")
    verify(bundle, group, tag, signed=False)
    fingerprint = key_fingerprint(gpg, key)
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch) / "bundle"
        with zipfile.ZipFile(bundle) as archive:
            archive.extractall(root)
        for module in MODULES:
            for name in payload_names(group, module, tag):
                path = root / name
                # 口令由 gpg-agent / pinentry 向本机用户询问，这里不接触。
                result = subprocess.run([gpg, "--batch", "--yes", "--armor", "--detach-sign", "--local-user", fingerprint,
                                         "--output", str(path) + ".asc", str(path)])
                require(result.returncode == 0, f"gpg failed to sign {name}")
        output.parent.mkdir(parents=True, exist_ok=True)
        write_zip(root, output)
    verify(output, group, tag, signed=True, gpg=gpg)
    return fingerprint


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("package", "verify", "sign"):
        command = commands.add_parser(name)
        command.add_argument("--group", required=True, help="Central 已验证的命名空间，如 io.github.<user>")
        command.add_argument("--tag", required=True)
        if name == "package":
            command.add_argument("--source", type=Path, required=True, help="已检出 tag 并执行过 publishAllPublicationsToProjectLocalRepository 的源码目录")
            command.add_argument("--output", type=Path, required=True)
        else:
            command.add_argument("--bundle", type=Path, required=True)
        if name == "verify":
            command.add_argument("--signed", action="store_true")
            command.add_argument("--gpg", default=None, help="同时用该 gpg 验证每个 .asc")
        if name == "sign":
            command.add_argument("--key", required=True, help="本机 gpg 私钥的 key id、指纹或邮箱")
            command.add_argument("--output", type=Path, required=True)
            command.add_argument("--gpg", default="gpg")
    args = parser.parse_args(argv)
    try:
        if args.command == "package":
            print(package(args.source, args.group, args.tag, args.output))
        elif args.command == "verify":
            verify(args.bundle, args.group, args.tag, signed=args.signed, gpg=args.gpg)
            print("Bundle verified")
        else:
            fingerprint = sign(args.bundle, args.group, args.tag, args.key, args.output, args.gpg)
            print(f"Signed with key {fingerprint}\n{args.output}\n"
                  "Make sure the public key is published on keyserver.ubuntu.com, keys.openpgp.org or pgp.mit.edu before uploading.")
    except BundleError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
