"""Builds the Spendable hype Shorts (5s and 7s) from rich.mp4 / broke.mp4 in this folder.

Run from this folder:  python -I build.py
"""
import math, random, struct, subprocess, wave, os

SR = 48000
HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(HERE)

# ---------------------------------------------------------------- audio synth

def blank(sec):
    return [0.0] * int(sec * SR)

def mix(buf, samples, at, gain=1.0):
    i0 = int(at * SR)
    for i, s in enumerate(samples):
        j = i0 + i
        if 0 <= j < len(buf):
            buf[j] += s * gain

def kick(heavy=False):
    n = int((0.9 if heavy else 0.32) * SR)
    out, ph = [], 0.0
    for i in range(n):
        t = i / SR
        f = 45 + 110 * math.exp(-t * (18 if heavy else 30))
        ph += 2 * math.pi * f / SR
        out.append(math.sin(ph) * math.exp(-t * (4 if heavy else 9)))
    return out

def noise_burst(dur, decay, lp=0.0, seed=1):
    rnd = random.Random(seed)
    out, y = [], 0.0
    for i in range(int(dur * SR)):
        t = i / SR
        x = rnd.uniform(-1, 1)
        y = y + (1 - lp) * (x - y) if lp else x
        out.append(y * math.exp(-t * decay))
    return out

def crackle(dur, density, seed):
    rnd = random.Random(seed)
    out = [0.0] * int(dur * SR)
    for i in range(len(out)):
        if rnd.random() < density / SR:
            amp = rnd.uniform(0.3, 1.0) * (1 - i / len(out))
            for k in range(min(120, len(out) - i)):
                out[i + k] += amp * rnd.uniform(-1, 1) * math.exp(-k / 18)
    return out

def click():
    return [math.sin(2 * math.pi * 1800 * i / SR) * math.exp(-i / SR * 70) for i in range(int(0.06 * SR))]

def riser(dur):
    rnd = random.Random(7)
    out, ph = [], 0.0
    for i in range(int(dur * SR)):
        t = i / SR
        p = t / dur
        f = 220 * (8 ** p)
        ph += 2 * math.pi * f / SR
        out.append((0.6 * math.sin(ph) + 0.4 * rnd.uniform(-1, 1) * p) * p ** 1.5)
    return out

def tone(freq, dur, harmonics=6, vibrato=0.0, attack=0.02, release=0.08, wah=False):
    out, ph = [], 0.0
    n = int(dur * SR)
    for i in range(n):
        t = i / SR
        f = freq * (1 + vibrato * math.sin(2 * math.pi * 5.5 * t))
        ph += 2 * math.pi * f / SR
        s = sum(math.sin(ph * h) / h for h in range(1, harmonics + 1))
        env = min(1, t / attack) * min(1, (dur - t) / release)
        if wah:
            env *= 0.55 + 0.45 * min(1, t / 0.12)
        out.append(s * env * 0.5)
    return out

def arpeggio(start, end, bpm=150):
    """Bright square-ish arpeggio + kicks + hats for the hype section."""
    seq = [523.25, 659.25, 783.99, 1046.5, 783.99, 659.25, 587.33, 783.99]
    step = 60 / bpm / 2
    events, t, k = [], start, 0
    while t < end - 0.05:
        events.append(("note", t, seq[k % len(seq)]))
        if k % 2 == 0:
            events.append(("kick", t, None))
        else:
            events.append(("hat", t, None))
        t += step
        k += 1
    return events, step

def build_audio(path, total, tap, reveal, pops, broke_at, broke_len):
    buf = blank(total)
    mix(buf, click(), tap, 0.5)
    mix(buf, riser(reveal - tap), tap, 0.35)
    mix(buf, kick(heavy=True), reveal, 0.9)
    mix(buf, noise_burst(0.8, 5, lp=0.85, seed=3), reveal, 0.5)
    for n, p in enumerate(pops):
        mix(buf, noise_burst(0.12, 30, seed=10 + n), p, 0.45)
        mix(buf, crackle(0.7, 900, seed=20 + n), p + 0.05, 0.25)
    events, step = arpeggio(reveal, broke_at)
    for kind, t, f in events:
        if kind == "note":
            mix(buf, tone(f, step * 0.9, harmonics=5, attack=0.004, release=0.03), t, 0.10)
        elif kind == "kick":
            mix(buf, kick(), t, 0.55)
        else:
            mix(buf, noise_burst(0.05, 60, seed=int(t * 100)), t, 0.12)
    # Punchline: record scratch, then a sad trombone squeezed into the remaining time.
    scratch = []
    ph = 0.0
    rnd = random.Random(99)
    for i in range(int(0.22 * SR)):
        t = i / SR
        f = 900 - 3200 * t
        ph += 2 * math.pi * max(f, 80) / SR
        scratch.append((0.6 * math.sin(ph) + 0.4 * rnd.uniform(-1, 1)) * (1 - t / 0.22))
    mix(buf, scratch, broke_at, 0.6)
    notes = [196.0, 185.0, 174.61, 164.81]
    short = min(0.32, (broke_len - 0.25) / 5.2)
    t = broke_at + 0.2
    for i, f in enumerate(notes):
        last = i == len(notes) - 1
        d = (broke_at + broke_len - t - 0.02) if last else short
        mix(buf, tone(f, d, harmonics=8, vibrato=0.035 if last else 0.0, attack=0.03, release=0.25 if last else 0.05, wah=True), t, 0.45)
        t += short * 1.08
    # Soft clip + normalize, short fade at the very end.
    out = [math.tanh(1.3 * s) for s in buf]
    peak = max(abs(s) for s in out) or 1
    out = [s / peak * 0.89 for s in out]
    fade = int(0.08 * SR)
    for i in range(fade):
        out[-1 - i] *= i / fade
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<hh", int(s * 32767), int(s * 32767)) for s in out))

# ---------------------------------------------------------------- video

FONT = "C\\:/Windows/Fonts/impact.ttf"
TOP, CARD = 100, 590  # crop offsets (source px) for the GO framing and the result-card framing

def caption(name, text, start, end, y, color="white", size=96):
    # newline="\n": on Windows a "\r\n" renders as an extra blank line in drawtext.
    with open(f"{name}.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    pop = f"{size}*(1+0.35*max(0\\,1-(t-{start})/0.14))"
    return (
        f"drawtext=fontfile='{FONT}':textfile={name}.txt:text_align=C:line_spacing=6:"
        f"fontsize='{pop}':fontcolor={color}:borderw=9:bordercolor=black@0.95:"
        f"shadowx=0:shadowy=8:shadowcolor=black@0.5:"
        f"x=(w-text_w)/2:y={y}:enable='between(t\\,{start}\\,{end})'"
    )

def build(name, segments, caps, audio):
    """segments: (input 0=rich/1=broke, src_in, src_out, framing) where framing is
    ("pan", pan_start_within_segment) | ("card-shake",) | ("card-flash",)."""
    prep = "crop=1080:2340:0:0,pad=1080:2560:0:0:black,fillborders=bottom=220:mode=smear"
    parts, labels, total = [], [], 0.0
    for i, (inp, a, b, framing) in enumerate(segments):
        if framing[0] == "pan":
            y = (f"st(0\\,clip((t-{framing[1]})/0.65\\,0\\,1));"
                 f"{TOP}+{CARD - TOP}*ld(0)*ld(0)*(3-2*ld(0))")
            extra = ""
        else:
            y = f"{CARD}+22*sin(t*85)*max(0\\,1-t/0.35)"
            extra = ",fade=t=in:st=0:d=0.18:color=white" if framing[0] == "card-flash" else ""
        parts.append(f"[{inp}:v]trim=start={a}:end={b},setpts=PTS-STARTPTS,fps=30,{prep},crop=1080:1920:0:'{y}'{extra}[s{i}]")
        labels.append(f"[s{i}]")
        total += b - a
    parts.append("".join(labels) + f"concat=n={len(segments)}:v=1:a=0[cat]")
    parts.append("[cat]" + ",".join(caption(f"{name}_c{i}", *c) for i, c in enumerate(caps)) + ",format=yuv420p[v]")
    with open(f"{name}.graph", "w", encoding="utf-8") as f:
        f.write(";".join(parts))
    wav = f"{name}.wav"
    build_audio(wav, total, **audio)
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-i", "rich.mp4", "-i", "broke.mp4", "-i", wav,
        "-/filter_complex", f"{name}.graph", "-map", "[v]", "-map", "2:a",
        "-c:v", "libx264", "-preset", "slow", "-crf", "17", "-profile:v", "high", "-pix_fmt", "yuv420p",
        "-r", "30", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-t", f"{total:.3f}",
        "-movflags", "+faststart", f"{name}.mp4",
    ], check=True)
    print("built", name, f"{total:.2f}s")

LIME, TAN = "0xC6FF3D", "0xE0A86E"
CAP_Y = 300

build(
    "spendable_short_7s",
    segments=[
        (0, 1.4, 4.4, ("pan", 1.6)),      # GO -> charge -> card counts up, camera pans down
        (0, 7.0, 9.0, ("card-shake",)),   # jump to peak money rain
        (1, 6.6, 8.6, ("card-flash",)),   # white flash -> poop storm
    ],
    caps=[
        ("HOW MUCH CAN I\nACTUALLY SPEND?", 0.05, 1.6, 1040),
        ("IT KNOWS\nYOUR BILLS", 1.75, 3.0, CAP_Y),
        ("SPA DAY:\nFUNDED", 3.0, 5.0, CAP_Y, LIME, 110),
        ("OR...\nNOT.", 5.0, 7.0, CAP_Y, TAN, 120),
    ],
    audio=dict(tap=0.5, reveal=1.7, pops=[2.5, 3.0, 3.6, 4.2], broke_at=5.0, broke_len=2.0),
)

build(
    "spendable_short_5s",
    segments=[
        (0, 1.75, 4.15, ("pan", 1.25)),
        (0, 7.2, 8.3, ("card-shake",)),
        (1, 6.9, 8.4, ("card-flash",)),
    ],
    caps=[
        ("HOW MUCH CAN I\nACTUALLY SPEND?", 0.0, 1.3, 1040),
        ("SPA DAY:\nFUNDED", 1.4, 3.5, CAP_Y, LIME, 110),
        ("OR...\nNOT.", 3.5, 5.0, CAP_Y, TAN, 120),
    ],
    audio=dict(tap=0.15, reveal=1.35, pops=[2.4, 2.9], broke_at=3.5, broke_len=1.5),
)
