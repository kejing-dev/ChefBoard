"""Offline loudness evidence (B20 / ui_gates loudness, build.md「只有短促反馈音的应用」): renders, at the default volume
stop (中 = 70 %) with the app's gains (Graph.sound: 咔 0.55, 笃 0.70 + 嗒 0.75 on landing, 笃 0.65 docked, bell 1.25) and
±5 % rate, one instance per kind (a new one cuts the previous, 3 ms fade), through the same −1 dBFS limiter as Mixer.kt:
a cooking minute — floating a timer, +5 +1, landing it, a second one, the bell every 1.2 s for 12 s, stop, back to the
rack — then 5 quick taps of the same kind. kj-mbp-m5 has no audio loopback for the emulator, so this is rendered, not
recorded. Usage: python3 tools/loudness.py OUT.wav"""
import math, os, random, struct, sys, wave
SR = 48000
SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
VOL = 0.70
GAIN = {"tick": 0.55, "wood": 0.70, "click": 0.75, "bell": 1.25, "dock": 0.65}

def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return [v / 32768 for v in struct.unpack("<%dh" % w.getnframes(), w.readframes(w.getnframes()))]

def variants(kind):
    k = "wood" if kind == "dock" else kind
    return sorted(f[:-4] for f in os.listdir(SND) if f.startswith(k + "_") and f.endswith(".wav"))

def render(events, seconds):
    n = int(seconds * SR); L = [0.0] * n
    rnd = random.Random(7); voices = {}
    for t, kind in sorted(events):
        x = load(rnd.choice(variants(kind)))
        rate = 0.95 + rnd.random() * 0.1
        y = [x[min(len(x) - 1, int(i * rate))] for i in range(int(len(x) / rate))]
        o = int(t * SR)
        k = "wood" if kind == "dock" else kind
        if k in voices:  # cut the previous instance of this kind (3 ms fade)
            s0, prev = voices[k]
            f = SR * 3 // 1000
            for i in range(o, min(n, s0 + len(prev))):
                j = i - s0
                if 0 <= j < len(prev):
                    L[i] -= prev[j] * (1.0 if i - o >= f else (i - o) / f)
        g = GAIN[kind]
        yy = [v * g for v in y]
        voices[k] = (o, yy)
        for i, v in enumerate(yy):
            if o + i < n: L[o + i] += v
    # master + limiter (instant attack, 80 ms release) to −1 dBFS
    ceil = 0.891; env = 1.0; rel = 1 - 1 / (SR * 0.08); out = []
    for v in L:
        v *= VOL; pk = abs(v); want = ceil / pk if pk > ceil else 1.0
        env = min(env * rel + (1 - rel), want); out.append(max(-ceil, min(ceil, v * env)))
    return out

ev = [(0.5, "tick"), (0.9, "tick"), (1.3, "tick"), (2.4, "wood"), (2.4, "click"), (4.0, "tick"), (5.0, "wood"), (5.0, "click")]
ev += [(6.0 + 1.2 * k, "bell") for k in range(10)] + [(18.5, "dock")]
ev += [(20.0 + 0.12 * k, "tick") for k in range(5)]  # 5 quick taps of the same kind
ev += [(22.0 + 0.15 * k, "bell") for k in range(5)]  # 5 quick bells (one instance at a time)
y = render(ev, 24)
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in y))
pk = max(abs(v) for v in y); win = SR // 50
en = [sum(v * v for v in y[i:i + win]) / win for i in range(0, len(y) - win, win)]
thr = (pk * 0.0316) ** 2; voiced = [e for e in en if e >= thr]
rms = math.sqrt(sum(voiced) / len(voiced))
print("peak %.2f dBFS, voiced RMS %.2f dBFS (%d of %d 20 ms windows)" % (20 * math.log10(pk), 20 * math.log10(rms), len(voiced), len(en)))
