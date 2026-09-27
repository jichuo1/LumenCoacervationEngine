"""把来源工程里交互与动效的测试迁入 lumen-motion/src/test（一次性脚本，保留供追溯）。

用法：python tools/migrate_motion_tests_from_module.py [unit|contract] [文件名...]
- unit：纯策略单测，只做改名与 import 重建；
- （DiagnosticsLateCallbackTest 断言诊断页数据回调，属业务，不迁；SettingsPagePositionRefreshTest 只保留翻页器用例，手写为 PagePositionRefreshTest。）
- contract：源码契约测试，另外把断言里的来源工程写法翻译成抽离后的写法（见 TRANSLATE），
  之后逐个运行、人工核对：断言意图仍成立的保留，只断言来源工程业务弹窗/页面的用例删除并记入审查报告。
"""
import collections
import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('mm', os.path.join(HERE, 'migrate_motion_from_module.py'))
SRC_ROOT = os.path.normpath(os.path.join(os.path.dirname(HERE), '..', 'Bilibili_Innocent_Lab'))
APP_SRC = os.path.join(SRC_ROOT, r'Bilibili_Innocent_Lab\app\src')
PKG_DIR = r'java\com\Bilibili_Innocent_Lab\xposedmodule\ui'
MAIN = os.path.join(os.path.dirname(HERE), r'lumen-motion\src\main\java\com\lumen\coacervation\engine')
DEST = os.path.join(os.path.dirname(HERE), r'lumen-motion\src\test\java\com\lumen\coacervation\engine')
ROOT_PKG = 'com.lumen.coacervation.engine'

# 复用主脚本的重命名表
src = open(os.path.join(HERE, 'migrate_motion_from_module.py'), encoding='utf-8').read()
RENAMES = eval(re.search(r'RENAMES = (\[[\s\S]*?\n\])', src).group(1))
RENAMES += [('SettingsRevealScrollMotion', 'RevealScrollMotion'), ('SettingsRevealRequest', 'RevealRequest')]

UNIT = [
    ('activity/BubbleLayerMotionSpecTest.kt', 'motion.modal', None),
    ('activity/BubbleMotionStabilityTest.kt', 'motion.modal', None),
    ('activity/BubblePlacementSpecTest.kt', 'motion.modal', None),
    ('activity/DiagnosticsTransitionMotionTest.kt', 'motion.morph', 'ContainerMorphTransitionTest.kt'),
    ('activity/ExpansionMotionPolicyTest.kt', 'motion.expansion', None),
    ('activity/IconAnchoredMotionSpecTest.kt', 'motion.modal', None),
    ('activity/ModalTitleColorOwnersTest.kt', 'motion.modal', None),
    ('activity/ModernNavigationMotionTest.kt', 'widget', 'NavigationBarMotionTest.kt'),
    ('activity/NavigationGlowContinuityTest.kt', 'widget', None),
    ('activity/NavigationGlowOrientationTest.kt', 'widget', None),
    ('activity/NestedExpansionPolicyTest.kt', 'motion.expansion', None),
    ('activity/SettingsPageMotionPolicyTest.kt', 'motion.pager', 'PageMotionPolicyTest.kt'),
    ('activity/SettingsRevealScrollMotionTest.kt', 'motion.reveal', 'RevealScrollMotionTest.kt'),
    ('interaction/ElasticMotionGroupPolicyTest.kt', 'interaction', None),
    ('interaction/ElasticMotionPolicyTest.kt', 'interaction', None),
]

CONTRACT = [
    ('activity/BubbleLayerIntegrationTest.kt', 'motion.modal', None),
    ('activity/BubbleMotionIntegrationTest.kt', 'motion.modal', None),
    ('activity/ModalAnchorRegressionTest.kt', 'motion.modal', None),
    ('activity/ModalBackdropBlurSpecTest.kt', 'motion.modal', None),
    ('activity/ModalMotionRefinementTest.kt', 'motion.modal', None),
    ('activity/ModalTitleHandoffTest.kt', 'motion.modal', None),
    ('activity/ModalTitleDescriptionTest.kt', 'motion.modal', None),
    ('activity/NavigationMotionPolicyTest.kt', 'motion', 'InterruptibleMotionContractTest.kt'),
    ('activity/PredictiveBackApi33IsolationTest.kt', 'motion.modal', None),
    ('activity/SectionExpansionWiringTest.kt', 'motion.expansion', None),
    ('activity/SettingsBackupMotionSpecTest.kt', 'motion.morph', 'ContainerMorphSpecTest.kt'),
    ('activity/SettingsPageInterruptionContractTest.kt', 'motion.pager', 'PageInterruptionContractTest.kt'),
    ('interaction/ElasticCaptureGateTest.kt', 'interaction', None),
    ('widget/BubbleDrawableGeometryTest.kt', 'widget', None),
    ('skin/ModalSkinPositionContractTest.kt', 'motion.modal', None),
]

# 契约断言里的来源工程写法 → 抽离后的写法（只翻译"同一意图、换了名字"的部分）
TRANSLATE = [
    ('presentSizedModalDialog', 'present'),
    ('createModalContainer', 'createContainer'),
    ('dismissWithAnimation', 'dismiss'),
    ('modalAnchorBounds', 'anchorBounds'),
    ('modalSurfaceBounds', 'surfaceBounds'),
    ('dialogAnchoredClosers', 'anchoredClosers'),
    ('dialogScrims', 'scrims'),
    ('dialogSurfaceInsets', 'surfaceInsets'),
    ('activeConfirmDialog', 'activeDialog'),
    ('notifyPreparedSkinPositionChanged()', 'lumen.notifyPositionChanged()'),
    ('skinModalBackground(monetColors.surface, MODAL_CORNER_RADIUS_DP)', 'lumen.modalBackground(surfaceColor, style.cornerRadiusDp)'),
    ('MODAL_CORNER_RADIUS_DP * density', 'cornerRadiusPx'),
    ('AnchorStyle.CONTAINER', 'ModalAnchorStyle.CONTAINER'),
    ('AnchorStyle.BUBBLE', 'ModalAnchorStyle.BUBBLE'),
    ('morphAnchorBounds', 'anchorBounds'),
    ('NativeFrameLayout', 'FrameLayout'),
    ('NativeLinearLayout', 'LinearLayout'),
    ('NativeTextView', 'TextView'),
    ('SettingsUiSource', 'MotionSource'),
]

MODE = sys.argv[1] if len(sys.argv) > 1 else 'unit'
ONLY = set(sys.argv[2:])
counts = collections.Counter()

# 主源码的顶层符号表，用来重建 import
symbols = {}
for d, _, fs in os.walk(MAIN):
    for f in fs:
        if not f.endswith('.kt'):
            continue
        text = open(os.path.join(d, f), encoding='utf-8').read()
        pkg = re.search(r'^package ([\w.]+)', text, re.M).group(1)
        for m in re.finditer(r'^(?:internal |public )?(?:data |enum |sealed |abstract |open |value )*'
                             r'(?:class|object|interface|fun|val) (?:[\w.<>]+\.)?(\w+)', text, re.M):
            symbols.setdefault(m.group(1), pkg)
for name, sub_pkg in {'GlowConfig': 'touch', 'GlowFrame': 'touch', 'GlowState': 'touch', 'GlowShape': 'touch',
                      'reachablePileRoomPx': 'touch', 'TouchGlowRenderer': 'touch'}.items():
    symbols.setdefault(name, f'{ROOT_PKG}.{sub_pkg}')

for rel, subpkg, new_name in (UNIT if MODE == 'unit' else CONTRACT):
    name = new_name or os.path.basename(rel)
    if ONLY and name not in ONLY and os.path.basename(rel) not in ONLY:
        continue
    layer = 'test' if MODE == 'unit' else 'contractTest'
    path = os.path.join(APP_SRC, layer, PKG_DIR, *rel.split('/'))
    text = open(path, encoding='utf-8').read()
    for old, new in RENAMES:
        text = re.sub(r'\b' + old + r'\b', new, text)
    if MODE == 'contract':
        for old, new in TRANSLATE:
            text = text.replace(old, new)
    if MODE == 'contract':
        # 源码路径：来源工程按目录找，lumen-motion 按简单文件名找（MotionSource.file）
        text = text.replace('val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"\n        return SourceContract.read(path)',
                            'return MotionSource.file(name)')
        text = re.sub(r'SourceContract\.read\("(?:src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/)?ui/[\w/]*?(\w+)\.kt"\)',
                      r'MotionSource.file("\1")', text)
        # 来源工程里弹窗编排写在 MainActivity，抽离后在 LumenModalPresenter
        text = text.replace('MotionSource.file("MainActivity")', 'MotionSource.file("LumenModalPresenter")')
        text = text.replace('source("MainActivity")', 'source("LumenModalPresenter")')
    pkg = f'{ROOT_PKG}.{subpkg}'
    text = re.sub(r'^package [\w.]+', f'package {pkg}', text, count=1, flags=re.M)
    text = re.sub(r'^import com\.Bilibili_Innocent_Lab\.xposedmodule\.contract\.', f'import {ROOT_PKG}.contract.', text, flags=re.M)
    text = re.sub(r'^import com\.Bilibili_Innocent_Lab\.[\w.]+\n', '', text, flags=re.M)
    body = re.sub(r'^(package|import) .*\n', '', text, flags=re.M)
    code = re.sub(r'//.*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"', '', body)
    needs = set()
    for sym, sym_pkg in symbols.items():
        if sym_pkg != pkg and re.search(r'\b' + re.escape(sym) + r'\b', code):
            needs.add(f'import {sym_pkg}.{sym}')
    if 'MotionSource' in code:
        needs.add(f'import {ROOT_PKG}.contract.MotionSource')
    existing = set(re.findall(r'^import [^\n]+$', text, re.M))
    body_text = re.sub(r'^import [^\n]+\n', '', re.sub(r'^package [\w.]+\n', '', text, count=1, flags=re.M), flags=re.M).lstrip('\n')
    imports = sorted(existing | needs)
    text = f'package {pkg}\n\n' + '\n'.join(imports) + '\n\n' + body_text
    out_dir = os.path.join(DEST, *subpkg.split('.'))
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, name), 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    counts['files'] += 1
    left = sorted(set(re.findall(r'com\.Bilibili_Innocent_Lab[\w.]*|SettingsUiSource|MainActivity|monetColors', text)))
    if left:
        print(f'!! {name}: {left}')
print(dict(counts))
