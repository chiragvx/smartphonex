# Makes a propeller test sound that changes speed: holds, jumps and ramps, for tools/prop-sweep.mjs.
#   python tools/prop-sweep.py <out.wav>
# Writes <out.wav> and <out>.json (the schedule). Two blades, with harmonics and a quiet 14-pole motor tone (7x).
import json, math, random, struct, sys, wave

RATE = 44100
BLADES = 2
# (seconds, rpm at start, rpm at end). 0 rpm is silence.
SCHEDULE = [
    (3, 0, 0),
    (4, 3000, 3000), (4, 4500, 4500), (4, 6000, 6000), (4, 7500, 7500), (4, 9000, 9000), (4, 12000, 12000),
    (4, 5000, 5000),      # big jump down
    (4, 8000, 8000),      # jump up
    (6, 8000, 3000),      # throttle cut, ramping down
    (4, 3000, 10000),     # punch-out, ramping up
    (2, 10000, 10000),
    (3, 0, 0),
]

def main(out):
    random.seed(1)
    phase = 0.0  # revolution phase, in turns, kept continuous across changes
    frames = bytearray()
    for secs, a, b in SCHEDULE:
        n = secs * RATE
        for i in range(n):
            rpm = a + (b - a) * i / n
            rev = rpm / 60
            phase += rev / RATE
            v = 0.02 * random.uniform(-1, 1)
            if rpm > 0:
                p = 2 * math.pi * phase
                v += (0.20 * math.sin(BLADES * p) + 0.12 * math.sin(2 * BLADES * p + 1) +
                      0.05 * math.sin(3 * BLADES * p + 2) + 0.06 * math.sin(7 * p + 0.5))
            frames += struct.pack('<h', int(max(-1, min(1, v)) * 32000))
    w = wave.open(out, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes(bytes(frames)); w.close()
    with open(out.rsplit('.', 1)[0] + '.json', 'w') as f:
        json.dump({'blades': BLADES, 'segments': [{'s': s, 'from': a, 'to': b} for s, a, b in SCHEDULE]}, f)

main(sys.argv[1])
