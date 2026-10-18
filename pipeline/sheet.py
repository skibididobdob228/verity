"""Contact sheet of screenshots: python3 pipeline/sheet.py out.png cols cropSpec img1 img2 ...   (cropSpec: 'full' or 'x0,y0,x1,y1' as fractions)"""
import sys
from PIL import Image, ImageDraw
out, cols, crop = sys.argv[1], int(sys.argv[2]), sys.argv[3]
files = sys.argv[4:]
tiles = []
for f in files:
    im = Image.open(f).convert('RGB')
    if crop != 'full':
        x0, y0, x1, y1 = (float(v) for v in crop.split(','))
        im = im.crop((int(x0 * im.width), int(y0 * im.height), int(x1 * im.width), int(y1 * im.height)))
    tw = 1920 // cols
    im = im.resize((tw, int(im.height * tw / im.width)), Image.LANCZOS)
    ImageDraw.Draw(im).text((6, 4), f.split('/')[-1].rsplit('.', 1)[0], fill=(255, 255, 255), stroke_width=2, stroke_fill=(0, 0, 0))
    tiles.append(im)
rows = (len(tiles) + cols - 1) // cols
sheet = Image.new('RGB', (1920, rows * tiles[0].height), (20, 20, 20))
for i, t in enumerate(tiles):
    sheet.paste(t, ((i % cols) * t.width, (i // cols) * t.height))
sheet.save(out)
