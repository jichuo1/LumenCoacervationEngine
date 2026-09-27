"""把指定的顶层声明从 internal 改为 public（公开 API 面的唯一改动方式，便于审计）。

用法：python tools/open_api.py Name1 Name2 ...
只改顶层声明本身；成员的可见性保持原样（成员默认随类型公开，internal 成员仍是内部实现）。
"""
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOTS = [os.path.join(BASE, m, 'src', 'main', 'java') for m in ('lumen-engine', 'lumen-motion', 'lumen-controls')]
names = sys.argv[1:]
changed = {}
for dirpath, _, files in (w for root in ROOTS for w in os.walk(root)):
    for f in files:
        if not f.endswith('.kt'):
            continue
        path = os.path.join(dirpath, f)
        text = open(path, encoding='utf-8').read()
        new = text
        for name in names:
            pattern = (r'^internal ((?:sealed |data |enum |abstract |open |fun |value )*'
                       r'(?:class|interface|object)) ' + re.escape(name) + r'\b')
            new, n = re.subn(pattern, r'public \1 ' + name, new, flags=re.M)
            if n:
                changed[name] = os.path.relpath(path, BASE)
        if new != text:
            open(path, 'w', encoding='utf-8', newline='\n').write(new)
for name in names:
    print(f"{name:40s} {changed.get(name, '!! 未找到 internal 顶层声明')}")
