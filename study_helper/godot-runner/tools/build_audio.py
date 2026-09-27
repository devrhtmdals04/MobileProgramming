"""Small original synthesized cues; reproducible using the Python standard library."""
import math
import random
import struct
import wave
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "assets" / "audio"
OUT.mkdir(parents=True, exist_ok=True)
random.seed(13)
RATE = 22050


def write(name, seconds, sample):
    with wave.open(str(OUT / (name + ".wav")), "wb") as sound:
        sound.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        sound.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, sample(i/RATE))) * 28000))
                                  for i in range(int(RATE * seconds))))


write("coin", .27, lambda t: (math.sin(math.tau*1174*t)+.4*math.sin(math.tau*1760*t))*.4*math.exp(-t*18))
write("jump", .24, lambda t: math.sin(math.tau*(340*t+600*t*t))*.4*math.sin(math.pi*t/.24)**2)
write("slide", .26, lambda t: random.uniform(-1,1)*.2*math.sin(math.pi*t/.26)**2)
write("hit", .3, lambda t: (random.uniform(-1,1)*.4+math.sin(math.tau*95*t)*.4)*math.exp(-t*15))
write("land", .11, lambda t: random.uniform(-1,1)*.5*math.exp(-t*45))
write("start", .75, lambda t: sum(math.sin(math.tau*f*max(0,t-i*.10))*math.exp(-max(0,t-i*.10)*7)*.18
      if t >= i*.10 else 0 for i,f in enumerate([523.25,659.25,783.99,1046.5])))
print("Built six original sound cues")
