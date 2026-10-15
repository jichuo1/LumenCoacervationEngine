"""Build once, then verify the same revision-bound APK pair on every device and before publication."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

NAMES = {"app": "sample.apk", "test": "sample-tests.apk"}
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
REVISION = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})")


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def metadata_apk(path, variant):
    data = json.loads(path.read_text(encoding="utf-8"))
    package = data["applicationId"]
    if not PACKAGE.fullmatch(package) or data["variantName"] != variant or len(data["elements"]) != 1:
        raise ValueError("Expected one release APK and a valid application ID")
    name = data["elements"][0]["outputFile"]
    if Path(name).name != name or not name.endswith(".apk"):
        raise ValueError("APK metadata must reference a local APK filename")
    apk = path.parent / name
    if apk.resolve().parent != path.parent.resolve() or not apk.is_file():
        raise ValueError("APK is missing or outside its output directory")
    return package, apk


def create(directory, revision, app_metadata, test_metadata):
    if not REVISION.fullmatch(revision):
        raise ValueError("An exact Git revision is required")
    app_id, app = metadata_apk(app_metadata, "release")
    test_id, test = metadata_apk(test_metadata, "releaseAndroidTest")
    if test_id != app_id + ".test":
        raise ValueError("The test APK must belong to this application")
    directory.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": 1, "revision": revision, "application_id": app_id,
                "test_application_id": test_id, "runner": RUNNER, "apks": {}}
    for kind, source in (("app", app), ("test", test)):
        destination = directory / NAMES[kind]
        shutil.copyfile(source, destination)
        manifest["apks"][kind] = {"file": NAMES[kind], "sha256": digest(destination)}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return verify(directory, revision)


def verify(directory, revision):
    if not REVISION.fullmatch(revision):
        raise ValueError("An exact Git revision is required")
    path = directory / "manifest.json"
    if path.stat().st_size > 16 * 1024:
        raise ValueError("Artifact manifest exceeds its size budget")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest["schema"] != 1 or manifest["revision"] != revision or manifest["runner"] != RUNNER:
        raise ValueError("Artifact schema, source revision or runner mismatch")
    app_id = manifest["application_id"]
    if not PACKAGE.fullmatch(app_id) or manifest["test_application_id"] != app_id + ".test":
        raise ValueError("Artifact application IDs do not match")
    for kind, name in NAMES.items():
        record = manifest["apks"][kind]
        apk = directory / name
        if record["file"] != name or apk.resolve().parent != directory.resolve() or not apk.is_file():
            raise ValueError("Unexpected artifact path")
        if digest(apk) != record["sha256"]:
            raise ValueError(f"SHA-256 mismatch: {name}")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("create", "verify"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--app-metadata", type=Path,
                        default=Path("sample/build/outputs/apk/release/output-metadata.json"))
    parser.add_argument("--test-metadata", type=Path,
                        default=Path("sample/build/outputs/apk/androidTest/release/output-metadata.json"))
    args = parser.parse_args()
    try:
        result = create(args.directory, args.revision, args.app_metadata, args.test_metadata) if args.mode == "create" \
            else verify(args.directory, args.revision)
        print(f"Verified APK pair: {result['revision']} ({result['application_id']})")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"FAIL artifacts: {error}\n")
