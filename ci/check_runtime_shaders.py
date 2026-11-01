#!/usr/bin/env python3
"""Runs the launcher's own runtime-resource checks (MainActivity.kt: staticFilesInstalled and
the runtime shader gate) against assets/libopenmw/resources at build time, so an APK that
would stop at "OpenMW runtime assets are missing" fails in CI instead of on the device."""
import re, sys, pathlib
kt_path, res = sys.argv[1], pathlib.Path(sys.argv[2])
kt = pathlib.Path(kt_path).read_text(encoding="utf-8")
lines = kt.split("\n")
var = {}
for m in re.finditer(r'val (\w+) = File\(\s*Constants\.(?:RESOURCES,\s*"([^"]+)"|USER_FILE_STORAGE \+ "/resources/([^"]+)")\s*\)', kt):
    var[m.group(1)] = m.group(2) or m.group(3)
for m in re.finditer(r'val (\w+) = (\w+)\.readText\(\)', kt):
    if m.group(2) in var: var[m.group(1)] = var[m.group(2)]
def idx(pat, start=0):
    for i in range(start, len(lines)):
        if re.search(pat, lines[i]): return i
    sys.exit(f"pattern not found: {pat}")
a0 = idx(r"private fun staticFilesInstalled\(\)"); a1 = idx(r"^    (private )?fun ", a0 + 1)
b0 = idx(r"val waterVertexText = File\(")
b1 = max(i for i, l in enumerate(lines) if "Runtime OpenMW 0.51" in l)
fails = []
a_start = sum(len(l) + 1 for l in lines[:a0]); a_end = sum(len(l) + 1 for l in lines[:a1])
for m in re.finditer(r'File\(\s*Constants\.RESOURCES,\s*"([^"]+)"', kt[a_start:a_end]):
    if not (res / m.group(1)).exists(): fails.append(f"missing {m.group(1)}")
v = res / "version"
if not v.is_file() or not v.read_text().strip().startswith("0.51.0"): fails.append("resources/version is not 0.51.0")
for i in range(len(lines)):
    secA, secB = a0 <= i < a1, b0 <= i <= b1
    if not (secA or secB): continue
    for m in re.finditer(r'(!?)(\w+)(?:\.readText\(\))?\.contains\("((?:[^"\\]|\\.)*)"\)', lines[i]):
        neg, v, raw = m.group(1) == "!", m.group(2), m.group(3)
        if "${" in raw or v not in var: continue
        s = raw.encode().decode("unicode_escape")
        f = res / var[v]
        if not f.is_file(): fails.append(f"missing {var[v]}"); continue
        has = s in f.read_text(encoding="utf-8", errors="replace")
        want = (not neg) if secA else neg
        if has != want: fails.append(f"{var[v]}: must {'' if want else 'NOT '}contain {s[:90]!r} (MainActivity.kt:{i+1})")
fails = sorted(set(fails))
print("\n".join(fails) or "runtime resources pass the launcher's checks")
sys.exit(1 if fails else 0)
