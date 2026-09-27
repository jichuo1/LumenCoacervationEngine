#!/usr/bin/env python3
"""找"无条件执行、且引用了高于 minSdk 的平台类型"的指令。

判据来自 ART 的实现顺序：`check-cast` / `instance-of` 先解析类型再判 null，
所以一个值恒为 null 的 check-cast 照样会抛 NoClassDefFoundError——比方法体里
任何 `if (SDK >= N)` 和 runCatching 都早。2026-09-11 的崩溃就是这一条。

反过来，只出现在 SDK 分支内部的 invoke/sget（例如
`if (isAtLeast(R)) { window.insetsController... }`）执行不到就不解析，不是缺陷。
所以这里只报 check-cast / instance-of，以及我们自己方法签名里泄漏的新类型。

用法：python audit_checkcast.py <apk> <api-versions.xml> <dexdump.exe> <minSdk> [自己的类前缀]
      自己的类前缀默认 Lcom/lumen/coacervation/（引擎）；宿主审计自己的代码时传入自己的前缀。

报告需要人工分类：出现在 `*Api29/31/33` 隔离类里、或只在 SDK 判断之后才会加载的类里的，是安全的；
出现在普通类里、会被无条件执行的，才是缺陷（见 docs/ENGINEERING_RULES.md §2）。
来源：Bilibili Innocent Lab 2026-09-11 线上崩溃后编写的审计脚本。
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

OWN_PREFIX = "Lcom/lumen/coacervation/"
CLASS_RE = re.compile(r"^\s*Class descriptor\s*:\s*'([^']+)'")
NAME_RE = re.compile(r"^\s*name\s*:\s*'([^']+)'")
TYPE_RE = re.compile(r"^\s*type\s*:\s*'([^']+)'")
# 例：0012: check-cast v1, Landroid/window/OnBackInvokedDispatcher; // type@1234
CAST_RE = re.compile(r"\b(check-cast|instance-of)\b[^,]*(?:,\s*[^,]+)*?,\s*(Landroid/[A-Za-z0-9_/$]+;)")
ANY_ANDROID_RE = re.compile(r"(Landroid/[A-Za-z0-9_/$]+;)")


def load_since(api_versions: Path) -> dict[str, float]:
    root = ET.parse(api_versions).getroot()
    since: dict[str, float] = {}
    for node in root.findall("class"):
        name = node.get("name")
        if name is None or not name.startswith("android/"):
            continue
        value = node.get("since")
        since[name] = float(value) if value else 1.0
    return since


def newer(descriptor: str, since: dict[str, float], min_sdk: int) -> float | None:
    level = since.get(descriptor[1:-1])
    return level if level is not None and level > min_sdk else None


def main() -> None:
    global OWN_PREFIX
    apk, api_versions, dexdump, min_sdk = (
        Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), int(sys.argv[4])
    )
    if len(sys.argv) > 5:
        OWN_PREFIX = sys.argv[5]
    since = load_since(api_versions)
    casts: list[str] = []
    signatures: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as archive:
            dexes = [n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            for entry in dexes:
                archive.extract(entry, tmp)
                process = subprocess.run(
                    [str(dexdump), "-d", str(Path(tmp) / entry)],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    text=True, encoding="utf-8", errors="replace", check=False,
                )
                klass = None
                method = None
                own = False
                for line in process.stdout.splitlines():
                    class_match = CLASS_RE.match(line)
                    if class_match:
                        klass = class_match.group(1)
                        own = klass.startswith(OWN_PREFIX)
                        method = None
                        continue
                    if not own:
                        continue
                    name_match = NAME_RE.match(line)
                    if name_match:
                        method = name_match.group(1)
                        continue
                    type_match = TYPE_RE.match(line)
                    if type_match and method:
                        # 方法原型：参数或返回值里出现新类型意味着调用方也要解析它。
                        for descriptor in ANY_ANDROID_RE.findall(type_match.group(1)):
                            level = newer(descriptor, since, min_sdk)
                            if level is not None:
                                signatures.append(
                                    f"  API {level:g}  {descriptor[1:-1].replace('/', '.')}\n"
                                    f"          在签名里 ← {klass[1:-1].replace('/', '.')}#{method}"
                                )
                        continue
                    cast_match = CAST_RE.search(line)
                    if cast_match and method:
                        descriptor = cast_match.group(2)
                        level = newer(descriptor, since, min_sdk)
                        if level is not None:
                            casts.append(
                                f"  API {level:g}  {cast_match.group(1)} "
                                f"{descriptor[1:-1].replace('/', '.')}\n"
                                f"          ← {klass[1:-1].replace('/', '.')}#{method}"
                            )

    print("=== 致命形态：无条件 check-cast / instance-of 指向高于 minSdk 的类型")
    for row in sorted(set(casts)) or ["  （无）"]:
        print(row)
    print("\n=== 次级形态：我们自己的方法签名里带高于 minSdk 的类型")
    for row in sorted(set(signatures)) or ["  （无）"]:
        print(row)
    print(f"\nminSdk={min_sdk}")


if __name__ == "__main__":
    main()
