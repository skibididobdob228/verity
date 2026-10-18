"""Normal Verity (the yellow ball): GLB -> VMSH + a texture with the baked face removed.

The face is drawn by the mod at runtime (so it can blink, talk and change expression), so the
painted-on smiley has to go: every dark texel is replaced by the surrounding yellow.

  python3 pipeline/build_ball.py <in.glb> <out.vmesh> <out.png>
"""
import io
import sys
import numpy as np
from PIL import Image, ImageFilter
import glb
import vmesh

src, out_mesh, out_png = sys.argv[1:4]
g = glb.load(src)
pos = g['pos'].copy()
pos -= (pos.max(0) + pos.min(0)) / 2                      # centre on the origin
radius = float(np.linalg.norm(pos, axis=1).mean())
# The generated mesh is lumpy (radius varies by a few percent), which let parts of it poke through the face that
# the mod draws just above the surface. Push every vertex onto the exact unit sphere; the mod scales it.
pos /= np.linalg.norm(pos, axis=1, keepdims=True)
nrm = pos.copy()                                          # perfectly smooth shading
vmesh.write(out_mesh, pos, nrm, g['uv'], g['tris'])

img = Image.open(io.BytesIO(g['base_image'])).convert('RGB')
a = np.asarray(img).astype('f4')
luma = a.mean(2)
yellow = np.median(a[luma > 140], axis=0)
mask = Image.fromarray(((luma < 138) * 255).astype('u1')).filter(ImageFilter.MaxFilter(13))   # face strokes + halo
valid = 1.0 - np.asarray(mask, 'f4') / 255.0
num = np.asarray(Image.fromarray((a * valid[..., None]).astype('u1')).filter(ImageFilter.GaussianBlur(40)), 'f4')
den = np.asarray(Image.fromarray((valid * 255).astype('u1')).filter(ImageFilter.GaussianBlur(40)), 'f4')[..., None] / 255.0
fill = np.where(den > 0.02, num / np.maximum(den, 0.02), yellow)
clean = a * valid[..., None] + fill * (1 - valid[..., None])
clean = clean * 0.45 + yellow * 0.55                      # calm down the patchy baked lighting
Image.fromarray(np.clip(clean, 0, 255).astype('u1')).resize((512, 512), Image.LANCZOS).save(out_png, optimize=True)
print(f'[ball] {len(pos)} verts, {len(g["tris"])} tris, source radius {radius:.3f}, yellow {yellow.round().tolist()}')
