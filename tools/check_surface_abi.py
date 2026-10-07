#!/usr/bin/env python3
"""Compare modified public surface/selector JVM descriptors against released AARs; write only to an explicit output directory."""
import argparse
from pathlib import Path
import subprocess
import zipfile

CORE=("LumenSurfaceSession","LumenSurfaceBinding","LumenSurfaceOptions","LumenSurfaceSampling","LumenSurfaceSessionOptions",
      "LumenContentSource","LumenSurfaceState","LumenSurfaceDiagnostics","LumenSurfaceListener","LumenSurfacePresets")

def extract(aar,target):
    with zipfile.ZipFile(aar)as z:target.write_bytes(z.read("classes.jar"))
    return target

def descriptors(javap,jar,name):
    result=subprocess.run([str(javap),"-classpath",str(jar),"-public","-s",name],capture_output=True,text=True,check=True)
    previous="";members=set()
    for line in result.stdout.splitlines():
        line=line.strip()
        if line.startswith("descriptor:"):
            members.add((previous,line))
        elif line:previous=line
    return members

def main():
    p=argparse.ArgumentParser();p.add_argument("--old-core",required=True);p.add_argument("--new-core",required=True)
    p.add_argument("--old-motion",required=True);p.add_argument("--new-motion",required=True);p.add_argument("--javap",type=Path,required=True);p.add_argument("--output",type=Path,required=True)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    groups=[("core",a.old_core,a.new_core,["com.lumen.coacervation.engine.host."+name for name in CORE]),
            ("motion",a.old_motion,a.new_motion,["com.lumen.coacervation.engine.widget.LumenSlidingSelection"])]
    failed=False
    for group,old,new,names in groups:
        oldjar=extract(old,a.output/(group+"-released.jar"));newjar=extract(new,a.output/(group+"-candidate.jar"))
        for name in names:
            before=descriptors(a.javap,oldjar,name);after=descriptors(a.javap,newjar,name);missing=before-after
            print(name+f": {len(before)} released members, {len(after)} current members, {len(missing)} missing")
            for declaration,descriptor in sorted(missing):print("MISSING",declaration,descriptor)
            failed|=bool(missing)
    raise SystemExit(1 if failed else 0)

if __name__=="__main__":main()
