"""Draws the parcel texture: left half = a side of the box, right half = the taped lid (two flaps)."""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont


def font(name, size):
    """The logo wants a heavy sans; any machine without Noto gets PIL's own font."""
    try:
        return ImageFont.truetype('/usr/share/fonts/noto/' + name, size)
    except OSError:
        return ImageFont.load_default()

S = 128
rng = np.random.default_rng(7)
img = Image.new('RGB', (S * 2, S), (176, 132, 82))
a = np.asarray(img).astype('f4')
a += rng.normal(0, 5, a.shape[:2])[..., None]                       # paper grain
a += (np.sin(np.arange(S)[:, None] * 1.9) * 2.5)[..., None]          # corrugation
img = Image.fromarray(np.clip(a, 0, 255).astype('u1')); d = ImageDraw.Draw(img)
for x0 in (0, S):                                                    # worn edges
    d.rectangle([x0, 0, x0 + S - 1, S - 1], outline=(128, 92, 54), width=3)
# side: a strip of tape coming down from the lid, a shipping label, one small smiley stamp
d.rectangle([52, 0, 76, 34], fill=(214, 196, 150)); d.line([52, 34, 76, 34], fill=(170, 150, 110), width=2)
# the shipping label carries the trademark: VERITY(tm), the slogan, a barcode
d.rectangle([8, 50, 82, 110], fill=(236, 232, 220), outline=(90, 80, 70))
d.rectangle([8, 50, 82, 68], fill=(235, 204, 5))
d.text((13, 51), 'VERITY', font=font('NotoSans-Black.ttf', 14), fill=(28, 20, 6))
d.text((13 + int(d.textlength('VERITY', font=font('NotoSans-Black.ttf', 14))) + 1, 52), 'TM', font=font('NotoSans-Bold.ttf', 6), fill=(28, 20, 6))
d.text((12, 71), 'знает всё™', font=font('NotoSans-Bold.ttf', 9), fill=(60, 56, 52))
d.text((12, 83), 'друг-помощник', font=font('NotoSans-Regular.ttf', 7), fill=(90, 84, 78))
x = 12
for i, w in enumerate(rng.integers(1, 3, 30)):
    if x + w > 78: break
    if i % 2 == 0: d.rectangle([x, 95, x + w - 1, 106], fill=(30, 28, 26))
    x += int(w)
d.ellipse([88, 70, 116, 98], fill=(235, 204, 5), outline=(30, 20, 5), width=2)
d.ellipse([96, 78, 99, 84], fill=(20, 12, 4)); d.ellipse([105, 78, 108, 84], fill=(20, 12, 4))
d.arc([94, 76, 110, 92], 20, 160, fill=(20, 12, 4), width=2)
# lid: seam between the flaps with tape across it
d.line([S + 64, 0, S + 64, S], fill=(110, 78, 44), width=2)
d.rectangle([S + 52, 0, S + 76, S - 1], fill=(214, 196, 150))
d.line([S + 64, 0, S + 64, S], fill=(150, 128, 92), width=1)
# a faded red stamp on one flap: the trademark again, crooked
stamp = Image.new('RGBA', (54, 20), (0, 0, 0, 0)); sd = ImageDraw.Draw(stamp)
sd.rectangle([0, 0, 53, 19], outline=(150, 40, 30, 150), width=2)
sd.text((4, 2), 'VERITY™', font=font('NotoSans-Black.ttf', 11), fill=(150, 40, 30, 150))
stamp = stamp.rotate(-12, expand=True, resample=Image.BICUBIC)
img.paste(stamp, (S + 78 - 12, 86), stamp)
img.save(sys.argv[1], optimize=True)
