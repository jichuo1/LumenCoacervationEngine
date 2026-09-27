"""把 Bilibili Innocent Lab 模块里的交互与动效（长按弹性、可打断动画、打开/关闭形变）迁入 lumen-motion。

一次性脚本，保留供追溯。用法：python tools/migrate_motion_from_module.py <模块仓库根目录>
只做机械替换：改包名、去掉业务前缀的重命名、BetterAndroid → 原生、按符号表重建跨包 import。
需要人工改写的部分（弹窗呈现器、全屏形变控制器从 Activity 中抽出，AppCompat 类型判断的泛化）不在这里处理。
"""
import collections
import os
import re
import sys

SRC_ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.normpath(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '..', 'Bilibili_Innocent_Lab'))
UI = os.path.join(SRC_ROOT, r'Bilibili_Innocent_Lab\app\src\main\java\com\Bilibili_Innocent_Lab\xposedmodule\ui')
DEST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    r'lumen-motion\src\main\java\com\lumen\coacervation\engine')
ROOT_PKG = 'com.lumen.coacervation.engine'

# (源文件相对 ui/, 目标子包, 目标文件名)
FILES = [
    ('interaction/ElasticInteractionController.kt', 'interaction', None),
    ('interaction/ElasticMotionPolicy.kt', 'interaction', None),
    ('interaction/ElasticMotionGroupPolicy.kt', 'interaction', None),
    ('activity/NavigationMotionPolicy.kt', 'motion', 'InterruptibleMotion.kt'),
    ('activity/SettingsBackupMotionSpec.kt', 'motion.morph', 'ContainerMorphSpec.kt'),
    ('activity/SettingsBackupMotionHost.kt', 'motion.morph', 'ContainerMorphHost.kt'),
    ('activity/DiagnosticsTransitionOrigin.kt', 'motion.morph', 'ContainerMorphOrigin.kt'),
    ('activity/DiagnosticsEntryVisualSpec.kt', 'motion.morph', 'ContainerMorphEntrySpec.kt'),
    ('activity/IconAnchoredMotionController.kt', 'motion.modal', None),
    ('activity/IconAnchoredMotionLayer.kt', 'motion.modal', None),
    ('activity/IconAnchoredMotionSpec.kt', 'motion.modal', None),
    ('activity/BubbleMotionController.kt', 'motion.modal', None),
    ('activity/BubbleLayerMotionSpec.kt', 'motion.modal', None),
    ('activity/BubblePanelLayer.kt', 'motion.modal', None),
    ('activity/BubblePlacementSpec.kt', 'motion.modal', None),
    ('activity/BubbleSkinSurfaceView.kt', 'motion.modal', None),
    ('activity/BubbleSurfaceDrawable.kt', 'motion.modal', None),
    ('activity/BubbleIconProxy.kt', 'motion.modal', None),
    ('widget/BubbleDrawable.kt', 'widget', None),
    ('activity/ModalTitleMotion.kt', 'motion.modal', None),
    ('activity/ModalTitleDescriptions.kt', 'motion.modal', None),
    ('activity/ModalTitleColorOwners.kt', 'motion.modal', None),
    ('activity/ModalCardRoot.kt', 'motion.modal', None),
    ('activity/ModalBackdropBlur.kt', 'motion.modal', None),
    ('activity/ModalBackdropBlurSpec.kt', 'motion.modal', None),
    ('activity/PredictiveBackApi33.kt', 'motion.modal', None),
    ('activity/SettingsPagePager.kt', 'motion.pager', 'LumenPagePager.kt'),
    ('activity/SettingsPageMotionPolicy.kt', 'motion.pager', 'PageMotionPolicy.kt'),
    ('activity/SettingsPageTextChain.kt', 'motion.pager', 'PageTextChain.kt'),
    ('activity/SettingsHomeScrollView.kt', 'motion.pager', 'LumenPageScrollView.kt'),
    ('activity/SettingsUserScrollSession.kt', 'motion.pager', 'PageUserScrollSession.kt'),
    ('activity/SectionExpansionController.kt', 'motion.expansion', None),
    ('activity/ExpansionMotionPolicy.kt', 'motion.expansion', None),
    ('activity/NestedExpansionPolicy.kt', 'motion.expansion', None),
    ('activity/ModernNavigationBar.kt', 'widget', 'LumenNavigationBar.kt'),
    ('activity/ModernNavigationMotion.kt', 'widget', 'NavigationBarMotion.kt'),
    ('activity/LogSegmentScrubBar.kt', 'widget', 'LumenSegmentScrubBar.kt'),
    ('activity/CoverableRippleDrawable.kt', 'widget', None),
]

# 去掉业务前缀的重命名（长名在前，避免前缀被短名先替换）
RENAMES = [
    ('SettingsBackupTransitionTitleMode', 'ContainerMorphTitleMode'),
    ('SettingsBackupMotionFrameBuffer', 'ContainerMorphFrameBuffer'),
    ('SettingsBackupMotionGeometry', 'ContainerMorphGeometry'),
    ('SettingsBackupContentTiming', 'ContainerMorphContentTiming'),
    ('SettingsBackupMotionFrame', 'ContainerMorphFrame'),
    ('SettingsBackupMotionHost', 'ContainerMorphHost'),
    ('SettingsBackupMotionSpec', 'ContainerMorphSpec'),
    ('SettingsBackupMotionRect', 'MotionRect'),
    ('DiagnosticsTransitionOriginRegistry', 'ContainerMorphOriginRegistry'),
    ('DiagnosticsTransitionCoordinateMapper', 'ContainerMorphCoordinateMapper'),
    ('DiagnosticsMappedTransitionOrigin', 'MappedContainerMorphOrigin'),
    ('DiagnosticsTransitionOrigin', 'ContainerMorphOrigin'),
    ('DiagnosticsEntryVisualSpec', 'ContainerMorphEntrySpec'),
    ('NavigationMotionContinuation', 'InterruptibleMotionContinuation'),
    ('NavigationMotionSession', 'InterruptibleMotionSession'),
    ('NavigationMotionPolicy', 'InterruptibleMotionPolicy'),
    ('NavigationMotionPhase', 'InterruptibleMotionPhase'),
    ('SettingsPageMotionContinuation', 'PageMotionContinuation'),
    ('SettingsPageMotionLifecycle', 'PageMotionLifecycle'),
    ('SettingsPageUserNavigation', 'PageUserNavigation'),
    ('SettingsPageMotionPolicy', 'PageMotionPolicy'),
    ('SettingsSwitchTouchBounds', 'SwitchTouchBounds'),
    ('SettingsPageTextChain', 'PageTextChain'),
    ('SettingsPagePager', 'LumenPagePager'),
    ('SettingsHomeScrollView', 'LumenPageScrollView'),
    ('SettingsUserScrollSession', 'PageUserScrollSession'),
    ('ModernNavigationSurface', 'NavigationBarSurface'),
    ('ModernNavigationColors', 'NavigationBarColors'),
    ('ModernNavigationGesture', 'NavigationBarGesture'),
    ('ModernNavigationIntent', 'NavigationBarIntent'),
    ('ModernNavigationSpring', 'LumenSpring'),
    ('ModernNavigationMotion', 'NavigationBarMotion'),
    ('ModernNavigationBar', 'LumenNavigationBar'),
    ('LogSegmentScrubBar', 'LumenSegmentScrubBar'),
]

# 引擎本体（lumen-engine）里被引用的公开类型
ENGINE_SYMBOLS = {
    'GlowConfig': 'touch', 'GlowFrame': 'touch', 'GlowState': 'touch', 'GlowShape': 'touch',
    'reachablePileRoomPx': 'touch', 'TouchGlowRenderer': 'touch',
    'GlowLegibilityPolicy': 'glow', 'LiquidMotionSurfaceFrameProvider': 'liquid',
}

SDK = {'O': 'O', 'P': 'P', 'Q': 'Q', 'R': 'R', 'S': 'S', 'S_V2': 'S_V2', 'T': 'TIRAMISU',
       'U': 'UPSIDE_DOWN_CAKE', 'V': 'VANILLA_ICE_CREAM', 'BAKLAVA': 'BAKLAVA'}

counts = collections.Counter()


def sub(pattern, repl, text, key, flags=0):
    new, n = re.subn(pattern, repl, text, flags=flags)
    counts[key] += n
    return new


def rename(text):
    for old, new in RENAMES:
        text = sub(r'\b' + old + r'\b', new, text, 'rename')
    return text


# 第一遍：读入、重命名，建立 "顶层符号 → 包" 表
loaded = []
symbols = dict((k, f'{ROOT_PKG}.{v}') for k, v in ENGINE_SYMBOLS.items())
for rel, subpkg, new_name in FILES:
    text = open(os.path.join(UI, rel), encoding='utf-8').read()
    text = rename(text)
    pkg = f'{ROOT_PKG}.{subpkg}'
    for m in re.finditer(r'^(?:internal |public )?(?:data |enum |sealed |abstract |open |value )*'
                         r'(?:class|object|interface|fun|val|typealias) (?:[\w.<>]+\.)?(\w+)', text, re.M):
        name = m.group(1)
        if name not in ('run', 'invoke'):
            symbols.setdefault(name, pkg)
    loaded.append((rel, subpkg, new_name or os.path.basename(rel), text))

# boundsWithin 是 View 扩展函数，名字取的是接收者后的部分
symbols['boundsWithin'] = f'{ROOT_PKG}.motion.morph'

for rel, subpkg, name, text in loaded:
    pkg = f'{ROOT_PKG}.{subpkg}'
    text = sub(r'^package [\w.]+', f'package {pkg}', text, 'package', re.M)
    # 别名 import 保留别名、换成新的全名
    aliases = {}
    for m in re.finditer(r'^import com\.Bilibili_Innocent_Lab\.[\w.]+\.(\w+) as (\w+)\n', text, re.M):
        aliases[m.group(2)] = m.group(1)
    text = sub(r'^import com\.Bilibili_Innocent_Lab\.[\w.]+\n', '', text, 'import:old', re.M)
    text = sub(r'^import com\.highcapable\.[\w.]+\n', '', text, 'import:thirdParty', re.M)
    # BetterAndroid → 原生
    text = sub(r'AndroidVersion\.isAtLeast\(AndroidVersion\.(\w+)\)',
               lambda m: f'Build.VERSION.SDK_INT >= Build.VERSION_CODES.{SDK[m.group(1)]}', text, 'sdk')
    text = sub(r'AndroidVersion\.isLessThan\(AndroidVersion\.(\w+)\)',
               lambda m: f'Build.VERSION.SDK_INT < Build.VERSION_CODES.{SDK[m.group(1)]}', text, 'sdk')
    text = sub(r'AndroidVersion\.isAtMost\(AndroidVersion\.(\w+)\)',
               lambda m: f'Build.VERSION.SDK_INT <= Build.VERSION_CODES.{SDK[m.group(1)]}', text, 'sdk')
    text = sub(r'AndroidVersion\.code\b', 'Build.VERSION.SDK_INT', text, 'sdk')
    text = sub(r'(\w+)\.parentOrNull\(\)', r'(\1.parent as? ViewGroup)', text, 'parentOrNull')
    text = sub(r'(\w+)\.textToString\(\)', r'\1.text.toString()', text, 'textToString')
    text = sub(r'(?<![\w.])textToString\(\)', r'text.toString()', text, 'textToString')
    text = sub(r'\b(\w+)\.child(?:<[\w.]+>)?\(', r'\1.getChildAt(', text, 'child')
    text = sub(r'(?<![\w.])child(?:<[\w.]+>)?\(', r'getChildAt(', text, 'child')
    text = sub(r'^(\s*)textColor = (.+)$', r'\1setTextColor(\2)', text, 'textColor', re.M)
    text = sub(r'^@HikageView\n', '', text, 'hikage', re.M)
    text = sub(r'\bAppCompatTextView\b', 'TextView', text, 'appCompatTextView')
    text = sub(r'^@file:Suppress\("ReplaceWithAndroidVersion"\)\n', '', text, 'lint:suppress', re.M)
    text = sub(r'^[ \t]*@SuppressLint\("ReplaceWithAndroidVersion"\)\n', '', text, 'lint:suppress', re.M)
    # 标签与日志前缀
    text = sub(r'"bil\.elastic\.', '"lumen.elastic.', text, 'tag')
    text = sub(r'"BilibiliInnocentLab"', '"Lumen-Motion"', text, 'logTag')
    text = sub(r'"BIL-', '"Lumen-', text, 'prefix')
    # 重建跨包 import：只为本文件真正出现、且不在本包的符号补 import
    body = re.sub(r'^(package|import) .*\n', '', text, flags=re.M)
    code = re.sub(r'//.*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"', '', body)
    needs = set()
    for sym, sym_pkg in symbols.items():
        if sym_pkg != pkg and re.search(r'\b' + re.escape(sym) + r'\b', code):
            if sym in aliases.values():
                continue
            needs.add(f'import {sym_pkg}.{sym}')
    for alias, original in aliases.items():
        new_original = original
        for old, new in RENAMES:
            if original == old:
                new_original = new
        needs.add(f'import {symbols[new_original]}.{new_original} as {alias}')
    if 'Build.VERSION' in text and 'import android.os.Build\n' not in text:
        needs.add('import android.os.Build')
    if 'as? ViewGroup)' in text and 'import android.view.ViewGroup\n' not in text:
        needs.add('import android.view.ViewGroup')
    if needs:
        text = re.sub(r'^(package [\w.]+\n)', lambda m: m.group(1) + '\n' + '\n'.join(sorted(needs)) + '\n',
                      text, count=1, flags=re.M)
        counts['import:added'] += len(needs)
    text = re.sub(r'\n{3,}', '\n\n', text)
    leftover = re.findall(r'com\.Bilibili_Innocent_Lab|highcapable|AndroidVersion|parentOrNull|textToString|'
                          r'\bR\.(?:string|color|drawable|dimen)|HikageView|MainActivity|SwitchCompat|AppCompat', text)
    if leftover:
        print(f'!! 残留 {subpkg}/{name}: {sorted(set(leftover))}')
    out_dir = os.path.join(DEST, *subpkg.split('.'))
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, name), 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    counts['files'] += 1

for key in sorted(counts):
    print(f'{key:22s} {counts[key]}')
