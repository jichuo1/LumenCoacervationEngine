#!/usr/bin/env python3
"""Trusted release attachment steps; tag builds run in a separate read-only job."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

REPO = "jichuo1/LumenCoacervationEngine"
MODULES = (
    "lumen-engine", "lumen-motion", "lumen-controls", "lumen-compose", "lumen-effects",
    "lumen-assets", "lumen-assets-lottie", "lumen-assets-pag", "lumen-assets-rive",
)
GROUP = "com.lumen.coacervation.engine"
SMOKE = ("smoke (27, default)", "smoke (31, google_apis)",
         "smoke (33, aosp_atd)", "smoke (34, google_apis)")
LIMITS = [
    "API27-32 provider/process animation notification window remains.",
    "Safety caps, tiny durations and later scale changes can limit velocity continuity.",
    "Third-party horizontal native motion requires the explicit stopper adapter.",
    "Emulator results do not establish OEM equivalence or long-running native heap bounds.",
    "Local cache/log benchmarks do not establish hosted CI speedups, GPU power or OEM equivalence.",
]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(args, **kwargs):
    result = subprocess.run(args, check=False, **kwargs)
    require(result.returncode == 0, f"Command failed: {args[0]} (exit {result.returncode})")
    return result


def github(path):
    # The official CLI owns authentication and redirects; never extract a credential.
    result = command(["gh", "api", "--hostname", "github.com", f"repos/{REPO}/{path}"],
                     text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return json.loads(result.stdout)


def tag_commit(tag):
    obj = github(f"git/ref/tags/{tag}")["object"]
    for _ in range(4):
        if obj["type"] == "commit":
            require(re.fullmatch(r"[0-9a-f]{40}", obj["sha"]), "Invalid source commit")
            return obj["sha"]
        require(obj["type"] == "tag", "Tag does not resolve to a commit")
        obj = github(f"git/tags/{obj['sha']}")["object"]
    raise RuntimeError("Excessively nested tag")


def jobs_for(run):
    jobs = []
    for page in range(1, 20):
        part = github(f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs?per_page=100&page={page}")["jobs"]
        jobs.extend(part)
        if len(part) < 100:
            return jobs
    raise RuntimeError("Too many workflow jobs")


def successful_run(workflow, commit, event, branch, needed):
    query = urllib.parse.urlencode({"head_sha": commit, "event": event, "status": "success", "per_page": 100})
    runs = github(f"actions/workflows/{workflow}/runs?{query}")["workflow_runs"]
    for run in runs:
        if (run["head_sha"] != commit or run["head_branch"] != branch or
                run["event"] != event or run["status"] != "completed" or run["conclusion"] != "success"):
            continue
        jobs = jobs_for(run)
        if all(len([j for j in jobs if j["name"] == name and j["conclusion"] == "success"]) == 1 and
               len([j for j in jobs if j["name"] == name]) == 1 for name in needed):
            return {"run_id": run["id"], "run_attempt": run["run_attempt"], "url": run["html_url"],
                    "head_sha": commit, "event": event, "head_branch": branch,
                    "jobs": [{"id": j["id"], "name": j["name"], "conclusion": j["conclusion"]}
                             for j in jobs if j["name"] in needed]}
    raise RuntimeError(f"No successful exact-source {workflow}/{event}/{branch} with required jobs")


def release_for(tag, draft):
    if draft:
        matches = []
        for page in range(1, 20):
            releases = github(f"releases?per_page=100&page={page}")
            matches.extend(item for item in releases if item["tag_name"] == tag)
            if len(releases) < 100:
                break
        else:
            raise RuntimeError("Too many releases to identify the draft")
        require(len(matches) == 1, "Expected one existing release for the tag")
        release = matches[0]
    else:
        release = github(f"releases/tags/{tag}")
    require(release["tag_name"] == tag and release["draft"] == draft and not release["prerelease"],
            "Release must be the existing matching draft/published stable release")
    return release


def names(tag):
    libraries = {f"{module}-{tag}{suffix}" for module in MODULES for suffix in (".aar", "-sources.jar")}
    data = libraries | {f"lumen-maven-{tag}.zip", f"REVIEW_{tag}.md"}
    return data, f"VERIFICATION_{tag}.json"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def checksums(directory):
    files = sorted(p for p in directory.iterdir() if p.is_file() and p.name != "SHA256SUMS.txt")
    (directory / "SHA256SUMS.txt").write_text("".join(f"{sha(p)}  {p.name}\n" for p in files))


def check_source(tag, source):
    version = re.findall(r"^lumen\.version=(.+)$", (source / "gradle.properties").read_text(), re.M)
    require(version == [tag], "Tag must match source lumen.version")
    found = set(re.findall(r'^include\(":(lumen-[a-z0-9-]+)"\)', (source / "settings.gradle.kts").read_text(), re.M))
    require(found == set(MODULES), "Expected exactly nine supported library modules")
    commit = command(["git", "-C", str(source), "rev-parse", "HEAD"], text=True, stdout=subprocess.PIPE).stdout.strip()
    require(commit == tag_commit(tag), "Checked-out source is not the immutable tag commit")
    return commit


def preflight(tag, source):
    commit = check_source(tag, source)
    # The build job's contents:read token cannot see private draft releases.
    # Bind the existing draft only in the separate contents:write publish job.
    return {"version": tag, "source_commit": commit,
            "ci": successful_run("ci.yml", commit, "push", "main", ("gate",)),
            "demo_push": successful_run("demo.yml", commit, "push", "main", ("build", *SMOKE))}


def pom_identity(data, group, module, tag):
    root = ET.fromstring(data)
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for key, expected in (("groupId", group), ("artifactId", module), ("version", tag)):
        require(root.findtext(f"m:{key}", namespaces=ns) == expected, f"Incorrect POM {module}/{key}")


def package(tag, source, plan, output):
    require(plan["version"] == tag, "Plan version mismatch")
    commit = command(["git", "-C", str(source), "rev-parse", "HEAD"], text=True, stdout=subprocess.PIPE).stdout.strip()
    require(commit == plan["source_commit"], "Source changed after preflight")
    require(not output.exists() or not any(output.iterdir()), "Packaging directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    repo = source / "build/repo"
    prefix = Path(*GROUP.split("."))
    entries = []
    for module in MODULES:
        directory = repo / prefix / module / tag
        pom_identity((directory / f"{module}-{tag}.pom").read_bytes(), GROUP, module, tag)
        metadata = json.loads((directory / f"{module}-{tag}.module").read_text())
        require(all(metadata["component"].get(key) == value for key, value in
                    (("group", GROUP), ("module", module), ("version", tag))), "Gradle metadata identity mismatch")
        for suffix in (".aar", "-sources.jar"):
            artifact = directory / f"{module}-{tag}{suffix}"
            with zipfile.ZipFile(artifact) as archive:
                require(archive.testzip() is None, "Invalid distribution archive")
                if suffix == ".aar":
                    require("classes.jar" in archive.namelist() and "AndroidManifest.xml" in archive.namelist(), "Incomplete AAR")
                else:
                    base = f"{module}/src/main/java/"
                    tracked = command(["git", "-C", str(source), "ls-tree", "-r", "--name-only", commit, base],
                                      text=True, stdout=subprocess.PIPE).stdout.splitlines()
                    tracked = [p for p in tracked if p.endswith((".kt", ".java"))]
                    require(bool(tracked), "Missing tracked module sources")
                    for path in tracked:
                        blob = command(["git", "-C", str(source), "show", f"{commit}:{path}"], stdout=subprocess.PIPE).stdout
                        require(archive.read(path[len(base):]) == blob, "Source archive differs from tag")
            shutil.copyfile(artifact, output / artifact.name)
        entries.extend(p for p in directory.iterdir() if p.is_file())
    with zipfile.ZipFile(output / f"lumen-maven-{tag}.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(entries):
            archive.write(path, path.relative_to(repo).as_posix())
    review = source / f"docs/RELEASE_{tag}_REVIEW.md"
    tracked_review = command(["git", "-C", str(source), "show", f"{commit}:docs/RELEASE_{tag}_REVIEW.md"], stdout=subprocess.PIPE).stdout
    require(review.read_bytes() == tracked_review, "Review differs from the tagged source")
    shutil.copyfile(review, output / f"REVIEW_{tag}.md")
    data, verification = names(tag)
    require({p.name for p in output.iterdir()} == data, "Unexpected package inventory")
    record = dict(plan, schema=1, modules=list(MODULES), min_sdk=27, contract_version=1,
                  automation_commit=os.environ.get("GITHUB_SHA"), limits=LIMITS,
                  release_demo={"status": "pending explicit Demo workflow dispatch at the release tag"},
                  jitpack={"status": "pending anonymous verification"},
                  assets={name: {"sha256": sha(output / name), "size": (output / name).stat().st_size} for name in sorted(data)})
    write_json(output / verification, record)
    checksums(output)


def asset_inventory(release):
    result = {}
    for asset in release["assets"]:
        name = asset["name"]
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,150}", name) and name not in result, "Unsafe/duplicate release asset")
        result[name] = asset
    return result


def verify_digest(asset, path):
    require(asset["size"] == path.stat().st_size and asset.get("digest") == "sha256:" + sha(path),
            f"GitHub size/digest differs: {asset['name']}")


def upload(tag, files):
    command(["gh", "release", "upload", tag, *map(str, files), "--repo", REPO, "--clobber"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def publish(tag, output):
    data, verification = names(tag)
    expected = data | {verification, "SHA256SUMS.txt"}
    require({p.name for p in output.iterdir()} == expected, "Expected exactly 22 attachment files")
    record = json.loads((output / verification).read_text())
    commit = tag_commit(tag)
    require(record["version"] == tag and record["source_commit"] == commit and set(record["assets"]) == data, "Package source identity mismatch")
    for name in data:
        require(record["assets"][name]["sha256"] == sha(output / name), "Prepared attachment checksum differs")
    verify_manifest(output, expected - {"SHA256SUMS.txt"})
    release = release_for(tag, True)
    require(record.get("release_id", release["id"]) == release["id"] and release["target_commitish"] == commit,
            "Draft release identity/target changed")
    require(set(asset_inventory(release)) <= expected, "Draft contains unrelated attachments")
    successful_run("ci.yml", commit, "push", "main", ("gate",))
    successful_run("demo.yml", commit, "push", "main", ("build", *SMOKE))
    record["release_id"] = release["id"]
    write_json(output / verification, record)
    checksums(output)
    upload(tag, [output / name for name in sorted(expected)])
    uploaded = asset_inventory(release_for(tag, True))
    require(set(uploaded) == expected, "Draft attachment inventory incomplete")
    for name in expected:
        verify_digest(uploaded[name], output / name)
    require(tag_commit(tag) == commit, "Tag changed during upload")
    payload = json.dumps({"tag_name": tag, "target_commitish": commit, "draft": False})
    command(["gh", "api", "--hostname", "github.com", "--method", "PATCH",
             f"repos/{REPO}/releases/{release['id']}", "--input", "-"],
            input=payload, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    published = release_for(tag, False)
    require(published["id"] == release["id"] and published["target_commitish"] == commit,
            "Published release identity/target differs")
    print("Published all 22 verified library attachments. Explicitly dispatch demo.yml with ref=tag and inputs.tag=tag before finalize.")


def verify_manifest(directory, allowed, mutable_verification=None):
    found = {}
    for line in (directory / "SHA256SUMS.txt").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9_.-]{0,150})", line)
        require(match and match[2] not in found, "Invalid/duplicate SHA256 manifest entry")
        found[match[2]] = match[1]
    require(set(found) in allowed if isinstance(allowed, tuple) else set(found) == allowed,
            "SHA256 manifest inventory differs")
    for name, digest in found.items():
        if name == mutable_verification:
            # Recover an interrupted JSON/SHA pair update. The JSON's GitHub digest,
            # source identity and every immutable artifact hash are checked separately.
            continue
        require(sha(directory / name) == digest, f"Checksum mismatch: {name}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def anonymous(url, hosts=("jitpack.io",)):
    # Public downloads never use a GitHub credential, including on redirects.
    opener = urllib.request.build_opener(NoRedirect())
    for _ in range(5):
        parsed = urllib.parse.urlsplit(url)
        require(parsed.scheme == "https" and parsed.hostname in hosts and parsed.port in (None, 443) and
                parsed.username is None and parsed.password is None, "Unexpected public download destination")
        try:
            with opener.open(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code in (301, 302, 303, 307, 308) and error.headers.get("Location"):
                url = urllib.parse.urljoin(url, error.headers["Location"])
                continue
            raise RuntimeError(f"Public download failed from {parsed.hostname}: HTTP {error.code}") from None
        except urllib.error.URLError:
            raise RuntimeError(f"Public download failed from {parsed.hostname}") from None
    raise RuntimeError("Too many public download redirects")


def jitpack(tag, commit):
    record = json.loads(anonymous(f"https://jitpack.io/api/builds/com.github.jichuo1/LumenCoacervationEngine/{tag}"))
    require(record.get("status") == "ok" and record.get("commit") == commit and record.get("version") == tag and
            record.get("isTag") is True and record.get("private") is False and set(record.get("modules", [])) == set(MODULES),
            "JitPack does not match the exact public tag/nine modules")
    poms = {}
    for module in MODULES:
        url = f"https://jitpack.io/com/github/jichuo1/LumenCoacervationEngine/{module}/{tag}/{module}-{tag}.pom"
        raw = anonymous(url)
        pom_identity(raw, "com.github.jichuo1.LumenCoacervationEngine", module, tag)
        poms[module] = {"url": url, "sha256": hashlib.sha256(raw).hexdigest()}
    return {"status": "verified", "commit": commit, "modules": list(MODULES), "poms": poms}


def finalize(tag, output):
    commit = tag_commit(tag)
    release = release_for(tag, False)
    try:
        demo = successful_run("demo.yml", commit, "workflow_dispatch", tag, ("build", *SMOKE, "publish"))
    except RuntimeError:
        demo = successful_run("demo.yml", commit, "release", tag, ("build", *SMOKE, "publish"))
    data, verification = names(tag)
    apk = f"lumen-demo-{tag}.apk"
    expected = data | {verification, "SHA256SUMS.txt", apk}
    assets = asset_inventory(release)
    require(set(assets) == expected, "Expected library attachments plus the gated Demo APK")
    require(not output.exists() or not any(output.iterdir()), "Finalize directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    for name, asset in assets.items():
        url = f"https://github.com/{REPO}/releases/download/{tag}/{urllib.parse.quote(name, safe='')}"
        require(asset["browser_download_url"] == url, "Unexpected release download URL")
        (output / name).write_bytes(anonymous(url, ("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")))
        verify_digest(asset, output / name)
    record = json.loads((output / verification).read_text())
    require(record["version"] == tag and record["source_commit"] == commit and set(record["assets"]) in (data, data | {apk}), "Published verification identity differs")
    require(record["release_id"] == release["id"] and release["target_commitish"] == commit,
            "Published release identity/target differs")
    for name, metadata in record["assets"].items():
        require(metadata["sha256"] == sha(output / name), "Published artifact differs from its verification")
    verify_manifest(output, (data | {verification}, data | {verification, apk}), mutable_verification=verification)
    ci = successful_run("ci.yml", commit, "push", "main", ("gate",))
    jp = jitpack(tag, commit)
    # Keep the previously verified pair for a best-effort rollback if --clobber
    # deletes an old asset and the replacement upload fails.
    backup = output / ".restore"
    backup.mkdir()
    for name in (verification, "SHA256SUMS.txt"):
        shutil.copyfile(output / name, backup / name)
    record.update(ci=ci, release_demo=demo, jitpack=jp, status="verified libraries, gated Demo and JitPack",
                  finalized_by_run=os.environ.get("GITHUB_RUN_ID"), limits=LIMITS,
                  assets={name: {"sha256": sha(output / name), "size": (output / name).stat().st_size,
                                 "github_digest": assets[name]["digest"]} for name in sorted(data | {apk})})
    write_json(output / verification, record)
    checksums(output)
    require(tag_commit(tag) == commit, "Tag changed during final verification")
    try:
        upload(tag, [output / verification, output / "SHA256SUMS.txt"])
        final_release = release_for(tag, False)
        require(final_release["id"] == release["id"] and final_release["target_commitish"] == commit and
                tag_commit(tag) == commit, "Final release source identity changed")
        final_assets = asset_inventory(final_release)
        require(set(final_assets) == expected, "Final release asset inventory changed")
        for name in expected:
            verify_digest(final_assets[name], output / name)
    except RuntimeError as error:
        try:
            current = release_for(tag, False)
            require(current["id"] == release["id"] and current["target_commitish"] == commit,
                    "Cannot restore metadata to a different release")
            failures = []
            for name in (verification, "SHA256SUMS.txt"):
                try:
                    upload(tag, [backup / name])
                except RuntimeError:
                    failures.append(name)
            require(not failures, "Could not restore both previous metadata assets")
            restored = asset_inventory(release_for(tag, False))
            for name in (verification, "SHA256SUMS.txt"):
                require(name in restored, "Restored metadata asset is missing")
                verify_digest(restored[name], backup / name)
        except RuntimeError:
            error.add_note("Metadata rollback failed; restore the previous JSON/SHA pair before retrying finalize.")
        raise
    print("Final verification and SHA256 manifest updated after actual Demo and anonymous JitPack checks.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("step", choices=("check", "package", "publish", "finalize"))
    parser.add_argument("--tag", default=os.environ.get("RELEASE_TAG"))
    parser.add_argument("--source", type=Path)
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(os.environ.get("GITHUB_REPOSITORY", REPO) == REPO, "Unexpected repository")
    require(os.environ.get("GH_HOST", "github.com") == "github.com", "Unexpected GitHub host")
    require(args.tag and re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", args.tag), "Invalid semver tag")
    if args.step == "check":
        require(args.source is not None, "Source required")
        write_json(args.output, preflight(args.tag, args.source.resolve()))
    elif args.step == "package":
        require(args.source is not None and args.plan is not None, "Source and preflight plan required")
        package(args.tag, args.source.resolve(), json.loads(args.plan.read_text()), args.output)
    elif args.step == "publish":
        publish(args.tag, args.output)
    else:
        finalize(args.tag, args.output)


if __name__ == "__main__":
    main()
