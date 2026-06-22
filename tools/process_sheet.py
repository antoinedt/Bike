from PIL import Image
import numpy as np
from scipy import ndimage
import os

im = Image.open('tools/sheet_src').convert('RGB')
a = np.asarray(im).astype(np.int16); H, W, _ = a.shape
mx = a.max(2); mn = a.min(2); sat = mx - mn

# Background = low-saturation grey checkerboard. Cap brightness below the white
# helmet (max>247) and require min>=140 so the dark rider/bike are excluded.
bg_pred = (sat <= 30) & (mn >= 140) & (mx <= 247)
lbl, n = ndimage.label(bg_pred)
border = set(lbl[0].tolist()) | set(lbl[-1].tolist()) | set(lbl[:, 0].tolist()) | set(lbl[:, -1].tolist())
border.discard(0)
alpha = np.where(np.isin(lbl, list(border)), 0, 255).astype(np.uint8)

# Despeckle: keep only sizeable foreground blobs (drops stray checker bits and
# divider-line remnants) so every frame's registration is clean.
fg, fn = ndimage.label(alpha > 0)
if fn > 0:
    sizes = ndimage.sum(np.ones_like(fg), fg, index=np.arange(1, fn + 1))
    keep = (np.where(sizes > 800)[0] + 1).tolist()
    alpha = np.where(np.isin(fg, keep), alpha, 0).astype(np.uint8)

rgba = np.dstack([np.asarray(im), alpha])

# Four panels (dividers detected at x ~ 346, 695, 1043).
cuts = [(8, 343), (351, 692), (700, 1040), (1048, 1371)]
crops = []
for x0, x1 in cuts:
    sub = rgba[:, x0:x1]
    al = sub[:, :, 3]
    ys, xs = np.where(al > 40)
    bb = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    crop = sub[bb[1]:bb[3], bb[0]:bb[2]]
    # Anchor = wheel contact: median x of the lowest rows, and the ground row.
    cal = crop[:, :, 3]
    yy, xx = np.where(cal > 40)
    groundy = yy.max()
    low = yy >= groundy - max(2, int(crop.shape[0] * 0.06))
    anchorx = int(np.median(xx[low]))
    crops.append((Image.fromarray(crop, 'RGBA'), anchorx, groundy))

# Shared canvas; register every frame by its wheel anchor so the bike stays put.
maxw = max(c[0].width for c in crops); maxh = max(c[0].height for c in crops)
padx = int(maxw * 0.16); pady = int(maxh * 0.05)
CW, CH = maxw + 2 * padx, maxh + 2 * pady
anchorX, anchorY = CW // 2, CH - pady
out = []
for img, ax, gy in crops:
    canv = Image.new('RGBA', (CW, CH), (0, 0, 0, 0))
    canv.paste(img, (anchorX - ax, anchorY - gy), img)
    h = 320
    canv = canv.resize((round(CW * h / CH), h), Image.LANCZOS)
    out.append(canv)

outdir = 'tools/keyed'; os.makedirs(outdir, exist_ok=True)
for i, f in enumerate(out):
    f.save(f'{outdir}/cyclist_{i}.png')

# Magenta preview to spot halos/holes/jitter.
mag = Image.new('RGBA', (out[0].width * len(out), out[0].height), (255, 0, 255, 255))
for i, f in enumerate(out):
    mag.alpha_composite(f, (i * out[0].width, 0))
mag.convert('RGB').save('tools/keyed_preview.png')

# Also an overlay of all frames to eyeball bike registration (should be crisp).
ov = Image.new('RGBA', out[0].size, (255, 0, 255, 255))
for f in out:
    ov = Image.blend(ov, Image.alpha_composite(ov, f), 0.5)
ov.convert('RGB').save('tools/keyed_overlay.png')
print('frames', len(out), 'canvas', out[0].size)
