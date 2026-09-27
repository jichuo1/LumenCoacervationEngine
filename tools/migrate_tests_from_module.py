"""把来源工程里引擎相关的单元测试与源码契约测试迁入 lumen-engine/src/test（一次性脚本，保留供追溯）。"""
import collections
import os
import re
import sys

SRC_ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.normpath(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '..', 'Bilibili_Innocent_Lab'))
APP_SRC = os.path.join(SRC_ROOT, r'Bilibili_Innocent_Lab\app\src')
PKG_DIR = r'java\com\Bilibili_Innocent_Lab\xposedmodule'
DEST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    r'lumen-engine\src\test\java\com\lumen\coacervation\engine')
OLD = 'com.Bilibili_Innocent_Lab.xposedmodule'
NEW = 'com.lumen.coacervation.engine'

# 断言来源工程应用层接线的测试：不属于引擎，不迁移（见 docs/PORTABILITY_AUDIT.md §6）
SKIP = {'FairRunningMemoryCallSiteTest.kt', 'ModalSkinPositionContractTest.kt'}

jobs = []  # (源路径, 目标子包)
for layer in ('test', 'contractTest'):
    root = os.path.join(APP_SRC, layer, PKG_DIR)
    for dirpath, _, files in os.walk(root):
        for f in files:
            if not f.endswith('.kt') or f in SKIP:
                continue
            path = os.path.join(dirpath, f)
            text = open(path, encoding='utf-8').read()
            rel = os.path.relpath(dirpath, root).replace(os.sep, '/')
            if rel == 'contract' and f == 'SourceContract.kt':
                continue  # 重写
            if rel.startswith('ui/skin'):
                jobs.append((path, ''))
            elif rel == 'ui/activity' and re.search(r'\b(GlowConfig|GlowState|GlowShape|GlowFrame|reachablePileRoomPx|TouchGlowRenderer)\b', text):
                jobs.append((path, 'touch'))

counts = collections.Counter()


def sub(pattern, repl, text, key, flags=0):
    new, n = re.subn(pattern, repl, text, flags=flags)
    counts[key] += n
    return new


for path, subpkg in sorted(jobs):
    text = open(path, encoding='utf-8').read()
    pkg = NEW if not subpkg else f'{NEW}.{subpkg}'
    text = sub(r'^package [\w.]+', f'package {pkg}', text, 'package', re.M)
    text = sub(re.escape(OLD + '.contract.'), NEW + '.contract.', text, 'import:contract')
    text = sub(re.escape(OLD + '.ui.skin.engine.'), NEW + '.glow.', text, 'import:glow')
    text = sub(re.escape(OLD + '.ui.skin.'), NEW + '.', text, 'import:skin')
    text = sub(re.escape(OLD + '.ui.theme.MonetColors'), NEW + '.model.LumenPalette', text, 'import:palette')
    text = sub(re.escape(OLD + '.ui.interaction.ElasticGestureClaim'), NEW + '.interaction.ElasticGestureClaim', text, 'import:claim')
    text = sub(r'\bMonetColors\b', 'LumenPalette', text, 'type:palette')
    # 源码契约路径：相对 JAVA_ROOT 的 ui/skin/... → 引擎包内路径
    text = sub(r'"ui/skin/engine/', '"glow/', text, 'path:glow')
    text = sub(r'"ui/skin/', '"', text, 'path:skin')
    text = sub(r'src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/engine/',
               'src/main/java/com/lumen/coacervation/engine/glow/', text, 'path:glowFull')
    text = sub(r'src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/',
               'src/main/java/com/lumen/coacervation/engine/', text, 'path:skinFull')
    text = sub(r'src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/', 'src/main/java/com/lumen/coacervation/engine/',
               text, 'path:moduleFull')
    text = sub(r'"BIL-', '"Lumen-', text, 'prefix:BIL')
    out_dir = os.path.join(DEST, *([subpkg] if subpkg else []))
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, os.path.basename(path)), 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    counts['files'] += 1
    left = sorted(set(re.findall(r'com\.Bilibili_Innocent_Lab[\w.]*|"ui/activity/[\w/.]+"|\bModernPalette\b', text)))
    if left:
        print(f'!! {os.path.basename(path)}: {left}')

for key in sorted(counts):
    print(f'{key:18s} {counts[key]}')
