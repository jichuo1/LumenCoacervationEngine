"""把 Bilibili Innocent Lab 模块里的凝光视效引擎源码迁入 lumen-engine（一次性脚本，保留供追溯）。

用法：python tools/migrate_from_module.py <模块仓库根目录>
只做机械替换并逐项计数；需要人工改写的文件（宿主基类、资源色解析）不在这里处理。
"""
import collections
import os
import re
import sys

SRC_ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.normpath(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '..', 'Bilibili_Innocent_Lab'))
MODULE_JAVA = os.path.join(SRC_ROOT, r'Bilibili_Innocent_Lab\app\src\main\java\com\Bilibili_Innocent_Lab\xposedmodule')
DEST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    r'lumen-engine\src\main\java\com\lumen\coacervation\engine')
OLD_PKG = 'com.Bilibili_Innocent_Lab.xposedmodule'
NEW_PKG = 'com.lumen.coacervation.engine'

# (源文件相对 MODULE_JAVA, 目标子包)
FILES = []
skin = os.path.join(MODULE_JAVA, 'ui', 'skin')
for dirpath, dirnames, filenames in os.walk(skin):
    dirnames[:] = [d for d in dirnames if not d.startswith('.')]
    for name in filenames:
        if not name.endswith('.kt'):
            continue
        rel = os.path.relpath(os.path.join(dirpath, name), MODULE_JAVA)
        sub = os.path.relpath(dirpath, skin).replace(os.sep, '.')
        # 原 ui/skin/engine 放的是契约与悬浮栏组件；迁入后叫 glow，避免 engine.engine
        sub = 'glow' if sub == 'engine' else sub
        if rel.endswith(os.path.join('activity', 'SkinnedActivity.kt')):
            continue  # 人工改写为 host.LumenActivityDelegate
        if name == 'MaterialYouTokenResolver.kt':
            continue  # 人工改写：资源色改由 LumenPalette 提供
        FILES.append((rel, sub))
FILES.append((os.path.join('ui', 'interaction', 'ElasticGestureClaim.kt'), 'interaction'))
FILES.append((os.path.join('ui', 'activity', 'AdaptiveGlowPolicy.kt'), 'touch'))
FILES.append((os.path.join('ui', 'widget', 'TouchGlowRenderer.kt'), 'touch'))

SDK_LETTERS = {'O': 'O', 'P': 'P', 'Q': 'Q', 'R': 'R', 'S': 'S', 'S_V2': 'S_V2', 'T': 'TIRAMISU',
               'U': 'UPSIDE_DOWN_CAKE', 'V': 'VANILLA_ICE_CREAM', 'BAKLAVA': 'BAKLAVA'}

counts = collections.Counter()


def sub(pattern, repl, text, key, flags=0):
    new, n = re.subn(pattern, repl, text, flags=flags)
    counts[key] += n
    return new


for rel, subpkg in sorted(FILES):
    src = os.path.join(MODULE_JAVA, rel)
    text = open(src, encoding='utf-8').read()
    pkg = NEW_PKG if subpkg == '.' else f'{NEW_PKG}.{subpkg}'
    text = sub(r'^package [\w.]+', f'package {pkg}', text, 'package', re.M)
    # 引擎内部互相引用（engine 子包改名 glow 要先于通用替换）
    text = sub(re.escape(OLD_PKG + '.ui.skin.engine.'), NEW_PKG + '.glow.', text, 'import:glow')
    text = sub(re.escape(OLD_PKG + '.ui.skin.'), NEW_PKG + '.', text, 'import:skin')
    # 外部依赖改到引擎内
    text = sub(re.escape(OLD_PKG + '.ui.theme.MonetColors'), NEW_PKG + '.model.LumenPalette', text, 'import:palette')
    text = sub(re.escape(OLD_PKG + '.ui.interaction.ElasticGestureClaim'), NEW_PKG + '.interaction.ElasticGestureClaim',
               text, 'import:gestureClaim')
    text = sub(re.escape(OLD_PKG + '.ui.activity.GlowShape'), NEW_PKG + '.touch.GlowShape', text, 'import:glowShape')
    text = sub(r'\bMonetColors\b', 'LumenPalette', text, 'type:palette')
    # BetterAndroid / KavaRef → 原生
    text = sub(r'^import com\.highcapable\.[\w.]+\n', '', text, 'import:thirdParty', re.M)
    text = sub(r'AndroidVersion\.isAtLeast\(AndroidVersion\.(\w+)\)',
               lambda m: f'Build.VERSION.SDK_INT >= Build.VERSION_CODES.{SDK_LETTERS[m.group(1)]}', text, 'sdk:isAtLeast')
    text = sub(r'AndroidVersion\.code\b', 'Build.VERSION.SDK_INT', text, 'sdk:code')
    text = sub(r'classOf<([\w.]+)>\(\)', r'\1::class.java', text, 'classOf')
    text = sub(r'(\w+)\.parentOrNull\(\)', r'(\1.parent as? ViewGroup)', text, 'parentOrNull')
    text = sub(r'AppViewsActivity', 'Activity', text, 'appViewsActivity')
    # BetterAndroid 自带 lint 规则的压制注解，离开 BetterAndroid 后无意义
    text = sub(r'^[ \t]*@SuppressLint\("ReplaceWithAndroidVersion"\)\n', '', text, 'lint:suppress', re.M)
    if '@SuppressLint' not in text:
        text = sub(r'^import android\.annotation\.SuppressLint\n', '', text, 'import:suppressLint', re.M)
    # 线程 / RenderNode / 日志前缀
    text = sub(r'"BIL-', '"Lumen-', text, 'prefix:BIL')
    # 需要的新 import
    needs = []
    if 'Build.VERSION' in text and 'import android.os.Build\n' not in text:
        needs.append('import android.os.Build')
    if '(parent as? ViewGroup)' in text or 'parent as? ViewGroup)' in text:
        if 'import android.view.ViewGroup\n' not in text:
            needs.append('import android.view.ViewGroup')
    if re.search(r'\bActivity\b', text) and 'import android.app.Activity\n' not in text and counts['appViewsActivity']:
        if re.search(r'[:(,]\s*Activity\b', text):
            needs.append('import android.app.Activity')
    if needs:
        text = re.sub(r'^(package [\w.]+\n\n)', lambda m: m.group(1) + '\n'.join(needs) + '\n', text, count=1, flags=re.M)
        counts['import:added'] += len(needs)
    leftover = re.findall(r'com\.Bilibili_Innocent_Lab|highcapable|AndroidVersion|classOf<|parentOrNull|\bR\.(?:string|color|drawable)', text)
    if leftover:
        print(f'!! 残留 {rel}: {sorted(set(leftover))}')
    out_dir = os.path.join(DEST, *([] if subpkg == '.' else subpkg.split('.')))
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, os.path.basename(rel)), 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    counts['files'] += 1

for key in sorted(counts):
    print(f'{key:22s} {counts[key]}')
