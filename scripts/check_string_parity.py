#!/usr/bin/env python3
"""Fail if any values-* locale is missing keys present in values/strings.xml."""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
en_file = root / "values" / "strings.xml"
if not en_file.exists():
    print("values/strings.xml missing", file=sys.stderr)
    sys.exit(1)

def keys(path: Path):
    return set(re.findall(r'<string name="(\w+)">', path.read_text(encoding="utf-8")))

en = keys(en_file)
failed = False
for folder in sorted(root.glob("values-*")):
    sf = folder / "strings.xml"
    if not sf.exists():
        continue
    miss = sorted(en - keys(sf))
    extra = sorted(keys(sf) - en)
    if miss or extra:
        failed = True
        print(f"{folder.name}: missing={miss} extra={extra}")
if failed:
    sys.exit(1)
print(f"OK — {len(en)} keys parity across locales")
