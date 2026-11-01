#!/usr/bin/env python3
"""Two Android shader fixes the launcher requires but buildscripts/patches does not produce
(the upstream author applied them locally). Run on assets/libopenmw/resources after build.sh.

 - water: reflection/refraction UVs from the projected position instead of
   gl_FragCoord / screenRes, so they stay right at any render resolution
   (OPENMW_ANDROID_051_WATER_PROJECTIVE_SCREEN_COORDS).
 - objects: no explicit GL4ES fog in the local map and character preview, which turn fog off
   through gl_Fog and far = 1e7, a state the explicit fog uniforms do not see
   (OPENMW_ANDROID_051_LOCALMAP_OBJECT_FOG_BYPASS_V1).
"""
import sys, pathlib
res = pathlib.Path(sys.argv[1]) / "shaders" / "compatibility"

def edit(name, pairs, marker):
    p = res / name
    t = p.read_text(encoding="utf-8")
    if marker in t:
        print(f"{name}: already patched"); return
    for old, new in pairs:
        if t.count(old) != 1:
            sys.exit(f"{name}: expected exactly one {old!r}, found {t.count(old)}")
        t = t.replace(old, new)
    p.write_text(t, encoding="utf-8")
    print(f"{name}: patched")

M = "OPENMW_ANDROID_051_WATER_PROJECTIVE_SCREEN_COORDS"
edit("water.vert", [
    ("varying vec4  position;\n",
     f"varying vec4  position;\n// {M}\nvarying vec4 screenPosition;\n"),
    ("    gl_Position = modelToClip(gl_Vertex);\n",
     "    gl_Position = modelToClip(gl_Vertex);\n    screenPosition = gl_Position;\n"),
], M)
edit("water.frag", [
    ("varying vec4 position;\n",
     f"varying vec4 position;\n// {M}\nvarying vec4 screenPosition;\n"),
    ("    vec2 screenCoords = gl_FragCoord.xy / screenRes;\n",
     "    vec2 screenCoords = (screenPosition.xy / screenPosition.w) * 0.5 + 0.5;\n"),
], M)

L = "OPENMW_ANDROID_051_LOCALMAP_OBJECT_FOG_BYPASS_V1"
edit("objects.frag", [
    ("    gl_FragData[0] = applyFogAtPos(gl_FragData[0], passViewPos, far);\n",
     f"    // {L}: the local map and character preview set far = 1e7 to switch fog off\n"
     "    if (far < 1000000.0)\n"
     "        gl_FragData[0] = applyFogAtPos(gl_FragData[0], passViewPos, far);\n"),
], L)
