#!/usr/bin/env python3
"""Validate source links and distribution attachments against the canonical local notice files."""
import argparse
from pathlib import Path
import re
import zipfile

ROOT=Path(__file__).resolve().parents[1]
FILES=("LICENSE","NOTICE","THIRD_PARTY_NOTICES.md","third_party/AndroidLiquidGlassView-LICENSE.txt","third_party/Kyant-AndroidLiquidGlass-LICENSE.txt","third_party/Lottie-6.7.1-LICENSE.txt","third_party/PAG-4.5.98-LICENSE.txt","third_party/Rive-11.14.0-LICENSE.txt","third_party/Rive-11.14.1-LICENSE.txt")

def canonical(data):
    return data.decode("utf-8").replace("\r\n","\n").strip()

def verify(aar=None,sources=None):
    for name in FILES:
        if not (ROOT/name).is_file():
            raise ValueError(f"Missing notice file: {name}")
    text=(ROOT/"THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    for target in re.findall(r"\]\(([^)]+)\)",text):
        if "://" not in target and not (ROOT/target).is_file():
            raise ValueError(f"Broken local notice link: {target}")
    for path,prefix in ((aar,"assets/lumen/licenses/"),(sources,"")):
        if path is None:
            continue
        with zipfile.ZipFile(path) as archive:
            for name in FILES:
                if canonical(archive.read(prefix+name))!=canonical((ROOT/name).read_bytes()):
                    raise ValueError(f"Notice differs in {Path(path).name}: {name}")
    print("Notice links and requested archives verified")

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--aar");parser.add_argument("--sources")
    args=parser.parse_args();verify(args.aar,args.sources)
