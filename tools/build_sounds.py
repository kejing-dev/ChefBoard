"""ChefBoard event sounds from local Kenney CC0 packs (~/Downloads/cc0-sounds, no synthesis, no download):
trim / fade / filter / pitch / normalise only. Writes 16-bit mono 48 kHz wav into app/src/main/assets/sounds and
prints one row per file for build/sound-sources.md.  Run: ~/.factory-venv/bin/python tools/build_sounds.py"""
import os, subprocess, wave
import numpy as np
from scipy.signal import butter, sosfilt
SR = 48000
K = os.path.expanduser("~/Downloads/cc0-sounds")
IMP = K + "/kenney_impact-sounds/Audio/"; UI = K + "/kenney_interface-sounds/Audio/"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
os.makedirs(OUT, exist_ok=True)

def load(p):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", p, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(np.float64)

def trim(x, maxlen, thr=0.02, fade=0.03):
    a = np.abs(x); pk = a.max() or 1
    i0 = max(0, int(np.argmax(a > pk * thr)) - 24)
    x = x[i0:i0 + int(maxlen * SR)].copy()
    n = len(x); f = min(int(fade * SR), n // 3)
    x[n - f:] *= np.linspace(1, 0, f) ** 2
    x[:24] *= np.linspace(0, 1, 24)
    return x

def filt(x, kind, fc, order=2):
    return sosfilt(butter(order, fc, btype=kind, fs=SR, output="sos"), x)

def norm(x, db=-1.0):
    return x / (np.abs(x).max() or 1) * 10 ** (db / 20)

def pitch(x, ratio):
    n = int(len(x) / ratio); return np.interp(np.arange(n) * ratio, np.arange(len(x)), x)

def save(name, x, src, how):
    y = np.clip(x, -1, 1)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((y * 32767).astype("<i2").tobytes())
    pk = 20 * np.log10(np.abs(y).max() + 1e-9); rms = 20 * np.log10(np.sqrt(np.mean(y[np.abs(y) > 0.03 * np.abs(y).max()] ** 2)) + 1e-9)
    print(f"| {name}.wav | {src} | {how} | {len(y)/SR:.2f} s | peak {pk:.1f} / voiced RMS {rms:.1f} dBFS |")

print("| file | source (Kenney, CC0 1.0) | processing | length | level |\n|---|---|---|---|---|")
# 咔: +1 / +5 / 清零 (interface tick), 3 variants
for i, f in enumerate(["tick_001", "tick_002", "tick_004"], 1):
    save(f"tick_{i}", norm(trim(load(UI + f + ".ogg"), 0.12)), f"kenney_interface-sounds/{f}.ogg", "trim 120 ms, fade, −1 dBFS")
# 嗒: the clockwork click when a timer lands and starts
for i, f in enumerate(["click_003", "click_004"], 1):
    save(f"click_{i}", norm(trim(load(UI + f + ".ogg"), 0.10)), f"kenney_interface-sounds/{f}.ogg", "trim 100 ms, −1 dBFS")
# 笃: wood on the counter / back in the rack
for i, f in enumerate(["impactWood_light_000", "impactWood_light_002", "impactWood_medium_001"], 1):
    x = filt(trim(load(IMP + f + ".ogg"), 0.30, fade=0.08), "lowpass", 5000)
    save(f"wood_{i}", norm(x), f"kenney_impact-sounds/{f}.ogg", "trim 300 ms, low-pass 5 kHz, −1 dBFS")
# 叮铃: a small kitchen-timer bell — Kenney heavy bell pitched up ×1.7 (smaller bell), two strikes 110 ms apart,
# everything above 6 kHz removed (design sound.events 去 6 kHz 以上尖峰), 0.9 s
for i, f in enumerate(["impactBell_heavy_000", "impactBell_heavy_002"], 1):
    b = pitch(trim(load(IMP + f + ".ogg"), 1.6, thr=0.01, fade=0.25), 1.7)
    y = np.zeros(int(0.75 * SR)); o = int(0.11 * SR)
    y[:min(len(b), len(y))] += b[:len(y)]
    y[o:o + min(len(b), len(y) - o)] += 0.8 * b[:len(y) - o]
    y = filt(filt(y, "lowpass", 6000, 4), "highpass", 300)
    n = len(y); fd = int(0.20 * SR); y[n - fd:] *= np.linspace(1, 0, fd) ** 2
    save(f"bell_{i}", norm(y), f"kenney_impact-sounds/{f}.ogg", "pitch ×1.7, two strikes 110 ms apart, band 300 Hz–6 kHz, 0.75 s, −1 dBFS")
