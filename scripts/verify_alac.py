"""Decodes the ALAC frames written by Tlv8AlacTest with FFmpeg (via PyAV) and checks they
round-trip to the original PCM bit-for-bit. Run after `./gradlew testDebugUnitTest`:

    pip install av numpy && python scripts/verify_alac.py
"""
import math
import os
import struct
import sys
import tempfile

import av

path = os.path.join(tempfile.gettempdir(), "airsink-alac", "frames.bin")
data = open(path, "rb").read()

# ALAC magic cookie: 'alac' atom wrapping ALACSpecificConfig for 352-frame, 16-bit stereo 44.1 kHz.
config = struct.pack(">IBBBBBBHIII", 352, 0, 16, 40, 10, 14, 2, 255, 0, 0, 44100)
cookie = struct.pack(">I4sI", 12 + len(config), b"alac", 0) + config

ctx = av.CodecContext.create("alac", "r")
ctx.extradata = cookie
decoded = []
pos = 0
while pos < len(data):
    (n,) = struct.unpack(">I", data[pos:pos + 4])
    pkt = av.Packet(data[pos + 4:pos + 4 + n])
    pos += 4 + n
    for frame in ctx.decode(pkt):
        arr = frame.to_ndarray()  # planar s16p: shape (2, samples)
        for i in range(arr.shape[1]):
            decoded.append((int(arr[0][i]), int(arr[1][i])))

expected = []
for n in range(50 * 352 + 100):
    expected.append((int(math.sin(n * 2 * math.pi * 440 / 44100) * 12000),
                     int(math.sin(n * 2 * math.pi * 660 / 44100) * 9000)))

print(f"decoded {len(decoded)} frames, expected {len(expected)}")
mismatch = sum(1 for a, b in zip(decoded, expected) if a != b)
if len(decoded) != len(expected) or mismatch:
    print(f"MISMATCH: {mismatch} samples differ")
    sys.exit(1)
print("ALAC OK: bit-exact round trip through FFmpeg's decoder")
