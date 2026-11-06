#!/usr/bin/env python3
"""Generates the attention tones in app/src/main/res/raw as small 16-bit mono WAVs.

Soft sine notes with a gentle attack and exponential decay: noticeable in earbuds without
being harsh. They ship as app resources because the text-to-speech engine (a separate app)
plays them, and it can read our APK resources but not our private files.
Run: python3 tools/make_chimes.py
"""
import math, os, struct, wave

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

# name: [(hz, start_s, length_s, decay, level, overtone)]
TONES = {
    "chime_soft_bell": [(880.00, 0.00, 0.90, 4.5, 1.0, 0.35)],
    "chime_two_tone":  [(659.25, 0.00, 0.35, 7.0, 1.0, 0.25), (987.77, 0.16, 0.55, 5.5, 1.0, 0.25)],
    "chime_rising":    [(523.25, 0.00, 0.30, 8.0, 0.8, 0.25), (659.25, 0.10, 0.30, 8.0, 0.85, 0.25), (783.99, 0.20, 0.60, 5.0, 1.0, 0.25)],
    "chime_droplet":   [(1318.5, 0.00, 0.18, 18.0, 1.0, 0.0), (1760.0, 0.09, 0.30, 14.0, 0.7, 0.0)],
    "chime_marimba":   [(587.33, 0.00, 0.35, 11.0, 1.0, 0.6), (880.00, 0.13, 0.45, 10.0, 1.0, 0.6)],
    "chime_glass":     [(1567.98, 0.00, 1.00, 3.5, 0.6, 0.15), (2093.0, 0.00, 0.80, 4.5, 0.25, 0.0)],
}

def render(notes):
    total = max(at + ln for _, at, ln, *_ in notes)
    buf = [0.0] * (int(total * RATE) + 1)
    for hz, at, ln, decay, level, over in notes:
        start = int(at * RATE)
        for i in range(int(ln * RATE)):
            t = i / RATE
            env = min(1.0, t / 0.006) * min(1.0, (ln - t) / 0.03) * math.exp(-decay * t) * level
            w = math.sin(2 * math.pi * hz * t) + over * math.sin(4 * math.pi * hz * t) * math.exp(-decay * 1.5 * t)
            if start + i < len(buf):
                buf[start + i] += w * env
    peak = max(abs(x) for x in buf) or 1.0
    return [int(x * 0.7 / peak * 32767) for x in buf]

os.makedirs(OUT, exist_ok=True)
for name, notes in TONES.items():
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", s) for s in render(notes)))
    print(f"{path}  {os.path.getsize(path)//1024} KB")
