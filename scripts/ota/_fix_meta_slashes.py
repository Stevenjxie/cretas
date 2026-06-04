#!/usr/bin/env python3
"""Normalize Windows backslash paths in an expo-export metadata.json to forward
slashes so the Linux OTA manifest server can resolve asset/bundle files.

Usage: python _fix_meta_slashes.py <path/to/metadata.json>
"""
import json
import sys

p = sys.argv[1]
with open(p, encoding="utf-8") as f:
    d = json.load(f)

n = 0
for plat in d.get("fileMetadata", {}).values():
    if isinstance(plat, dict):
        if "bundle" in plat and "\\" in plat["bundle"]:
            plat["bundle"] = plat["bundle"].replace("\\", "/")
            n += 1
        for a in plat.get("assets", []):
            if "\\" in a.get("path", ""):
                a["path"] = a["path"].replace("\\", "/")
                n += 1

with open(p, "w", encoding="utf-8") as f:
    json.dump(d, f, separators=(",", ":"))

print(f"normalized {n} path(s)")
sample = d["fileMetadata"].get("android", {}).get("assets", [{}])
if sample:
    print("sample:", sample[0].get("path"))
