#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Transcribe Fengxing Road Side Road 2.m4a (六扇门 meeting) via faster-whisper large-v3 GPU."""
import os
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"  # must be before torch/ctranslate2 import (OMP#15)
import sys
import time
from faster_whisper import WhisperModel

WAV = r"D:\Temp\fengxing2.wav"
# resolve wav path robustly
for cand in [r"D:\Temp\fengxing2.wav", "/tmp/fengxing2.wav", os.path.expanduser("~/fengxing2.wav")]:
    if os.path.exists(cand):
        WAV = cand
        break

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
TXT = os.path.join(OUT_DIR, "transcript.txt")
SRT = os.path.join(OUT_DIR, "transcript.srt")

print(f"WAV: {WAV} exists={os.path.exists(WAV)}", flush=True)

t0 = time.time()
model = WhisperModel("large-v3", device="cuda", compute_type="float16")
print(f"Model loaded in {time.time()-t0:.1f}s", flush=True)

segments, info = model.transcribe(
    WAV,
    language="zh",
    beam_size=5,
    vad_filter=True,
    vad_parameters=dict(min_silence_duration_ms=500),
    initial_prompt="以下是六扇门食品工厂的会议录音，内容涉及生产、报工、采购、库存、成本、客户等业务。",
)
print(f"Detected language: {info.language} (p={info.language_probability:.2f}), duration={info.duration:.1f}s", flush=True)


def fmt_ts(s):
    h = int(s // 3600); m = int((s % 3600) // 60); sec = s % 60
    return f"{h:02d}:{m:02d}:{sec:06.3f}".replace(".", ",")


lines_txt = []
lines_srt = []
n = 0
for seg in segments:
    n += 1
    text = seg.text.strip()
    ts = f"[{int(seg.start//60):02d}:{int(seg.start%60):02d}]"
    lines_txt.append(f"{ts} {text}")
    lines_srt.append(f"{n}\n{fmt_ts(seg.start)} --> {fmt_ts(seg.end)}\n{text}\n")
    if n % 20 == 0:
        print(f"  ...{n} segments, t={seg.end:.0f}s", flush=True)

with open(TXT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines_txt) + "\n")
with open(SRT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines_srt))

print(f"DONE: {n} segments, {time.time()-t0:.1f}s total. Wrote {TXT} + {SRT}", flush=True)
