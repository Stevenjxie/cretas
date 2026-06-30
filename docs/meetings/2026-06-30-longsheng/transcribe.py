#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Transcribe Longsheng Building Block A 2.m4a (生产/库存/半成品/成品 会议) via faster-whisper large-v3 GPU."""
import os
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"  # before torch/ctranslate2 import (OMP#15)
import time
from faster_whisper import WhisperModel

WAV = r"D:\Temp\longsheng_a2.wav"
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
    initial_prompt="以下是食品工厂ERP系统的会议录音，内容涉及生产计划、逐道录入报工、结单、生产批次、半成品库、成品库、入库审核、库存生产、工序选择、成本核算等业务。",
)
print(f"lang={info.language} p={info.language_probability:.2f} dur={info.duration:.1f}s", flush=True)


def fmt_ts(s):
    h = int(s // 3600); m = int((s % 3600) // 60); sec = s % 60
    return f"{h:02d}:{m:02d}:{sec:06.3f}".replace(".", ",")


lines_txt, lines_srt, n = [], [], 0
for seg in segments:
    n += 1
    text = seg.text.strip()
    ts = f"[{int(seg.start//60):02d}:{int(seg.start%60):02d}]"
    lines_txt.append(f"{ts} {text}")
    lines_srt.append(f"{n}\n{fmt_ts(seg.start)} --> {fmt_ts(seg.end)}\n{text}\n")
    if n % 20 == 0:
        print(f"  ...{n} seg, t={seg.end:.0f}s", flush=True)

with open(TXT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines_txt) + "\n")
with open(SRT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines_srt))
print(f"DONE: {n} seg, {time.time()-t0:.1f}s. Wrote {TXT}", flush=True)
