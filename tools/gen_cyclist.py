import math, os
from PIL import Image, ImageDraw

SS = 3                      # supersample for anti-aliasing
W, H = 240*SS, 264*SS
FRAMES = 8

# ---- palette ----
JERSEY      = (76,130,230,255)
JERSEY_DARK = (40,82,160,255)
JERSEY_HI   = (120,165,240,255)
SHORTS      = (212,62,46,255)
SHORTS_DARK = (150,40,30,255)
SKIN        = (233,182,142,255)
SKIN_DARK   = (196,138,98,255)
HELMET      = (30,36,44,255)
HELMET_HI   = (74,86,102,255)
SHOE        = (16,20,26,255)
TIRE        = (18,22,30,255)
RIM         = (150,158,170,255)
RIM_D       = (70,78,90,255)
HUB         = (180,188,200,255)
FRAME       = (24,29,37,255)
FRAME_HI    = (70,80,94,255)
SHADOW      = (0,0,0,70)

def cap(d, a, b, width, color):
    """thick line with round caps"""
    d.line([a, b], fill=color, width=int(width))
    r = width/2
    for p in (a, b):
        d.ellipse([p[0]-r, p[1]-r, p[0]+r, p[1]+r], fill=color)

def ellipse(d, c, rx, ry, **kw):
    d.ellipse([c[0]-rx, c[1]-ry, c[0]+rx, c[1]+ry], **kw)

def lerp(a,b,t): return (a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t)

def draw_frame(theta):
    img = Image.new("RGBA",(W,H),(0,0,0,0))
    d = ImageDraw.Draw(img)
    cx = W/2
    ground = H*0.95
    rearR = H*0.215
    wc = (cx, ground-rearR)

    # ground shadow
    ellipse(d, (cx, ground+rearR*0.18), rearR*1.05, rearR*0.16, fill=SHADOW)

    bb = (cx, ground-rearR*0.52)
    crankR = rearR*0.46
    lped = (bb[0]-W*0.018, bb[1]+crankR*math.sin(theta))
    rped = (bb[0]+W*0.018, bb[1]+crankR*math.sin(theta+math.pi))

    hipY = wc[1]-rearR*0.74
    hipHalf = W*0.072
    lhip = (cx-hipHalf, hipY)
    rhip = (cx+hipHalf, hipY)

    torso = rearR*1.06
    shY = hipY-torso
    shHalf = W*0.125
    lsh = (cx-shHalf, shY)
    rsh = (cx+shHalf, shY)

    # ---- rear wheel ----
    rx, ry = rearR*0.80, rearR
    ellipse(d, wc, rx, ry, fill=TIRE)
    ellipse(d, wc, rx*0.86, ry*0.86, fill=(0,0,0,0), outline=RIM_D, width=int(SS*2.2))
    for i in range(12):
        a = i/12*2*math.pi
        e = (wc[0]+rx*0.82*math.cos(a), wc[1]+ry*0.82*math.sin(a))
        d.line([wc, e], fill=(RIM[0],RIM[1],RIM[2],120), width=int(SS*0.8))
    ellipse(d, wc, rearR*0.085, rearR*0.085, fill=HUB)

    # ---- frame ----
    cap(d, bb, (wc[0],wc[1]), W*0.022, FRAME)         # chainstay
    cap(d, bb, (cx, hipY+rearR*0.02), W*0.026, FRAME) # seat tube
    # cranks/pedals
    ellipse(d, bb, crankR*0.30, crankR*0.30, fill=FRAME)
    cap(d, bb, lped, W*0.013, FRAME)
    cap(d, bb, rped, W*0.013, FRAME)

    # ---- legs ----
    def leg(hip, ped, side, calf_col, thigh_col):
        lift = bb[1]-ped[1]
        mid = lerp(hip, ped, 0.5)
        knee = (mid[0]+side*W*0.05, mid[1]-rearR*0.04-lift*0.34)
        cap(d, hip, knee, rearR*0.20, thigh_col)      # thigh
        cap(d, knee, ped, rearR*0.135, calf_col)      # calf
        ellipse(d, ped, rearR*0.10, rearR*0.055, fill=SHOE)  # shoe
    leg(lhip, lped, -1, SKIN_DARK, SHORTS_DARK)
    leg(rhip, rped, +1, SKIN, SHORTS)

    # ---- shorts seat ----
    d.polygon([(lhip[0]-hipHalf*0.1, hipY), (rhip[0]+hipHalf*0.1, hipY),
               (rhip[0], hipY+rearR*0.12), (lhip[0], hipY+rearR*0.12)], fill=SHORTS)

    # ---- bars (peek) ----
    barY = shY+torso*0.42
    cap(d, (cx-W*0.14, barY), (cx+W*0.14, barY-H*0.01), W*0.012, FRAME)

    # ---- torso (back) ----
    d.polygon([lhip, lsh, (cx, shY-torso*0.06), rsh, rhip], fill=JERSEY)
    # shading: subtle darker right flank (outer side only), highlight left edge
    d.polygon([(cx+shHalf*0.4,shY+torso*0.04),(rsh[0],shY),rhip,(cx+hipHalf*0.4,hipY)], fill=JERSEY_DARK)
    cap(d, lsh, lhip, W*0.012, JERSEY_HI)
    # spine
    cap(d, (cx,hipY), (cx,shY+torso*0.1), W*0.012, JERSEY_DARK)
    # hem band
    cap(d, lhip, rhip, W*0.016, JERSEY_DARK)

    # ---- arms (reach forward/in toward the bars) ----
    def arm(sh, hand, col):
        elb = (lerp(sh,hand,0.55)[0], lerp(sh,hand,0.55)[1]+rearR*0.06)
        cap(d, sh, elb, W*0.05, JERSEY_DARK)
        cap(d, elb, hand, W*0.04, col)
        ellipse(d, hand, W*0.024, W*0.024, fill=SHOE)
    arm(lsh, (cx-W*0.085, barY+torso*0.04), SKIN_DARK)
    arm(rsh, (cx+W*0.085, barY+torso*0.04), SKIN)

    # ---- neck + head + helmet ----
    neck = (cx, shY+torso*0.02)
    headCy = shY-torso*0.22
    headW = W*0.115
    cap(d, neck, (neck[0], headCy+headW*0.3), W*0.05, SKIN_DARK)
    # helmet teardrop
    ellipse(d, (neck[0], headCy), headW*0.62, headW*0.7, fill=HELMET)
    d.polygon([(neck[0]-headW*0.5, headCy+headW*0.1),(neck[0]+headW*0.5,headCy+headW*0.1),
               (neck[0], headCy+headW*0.85)], fill=HELMET)
    # helmet highlight + vent
    ellipse(d, (neck[0]-headW*0.18, headCy-headW*0.18), headW*0.22, headW*0.26, fill=HELMET_HI)
    cap(d, (neck[0], headCy-headW*0.4),(neck[0], headCy+headW*0.4), W*0.012, (12,15,20,255))

    return img

outdir = "/home/user/Bike/app/src/main/res/drawable-nodpi"
os.makedirs(outdir, exist_ok=True)
frames=[]
for i in range(FRAMES):
    theta = i/FRAMES*2*math.pi
    im = draw_frame(theta)
    im = im.resize((W//SS, H//SS), Image.LANCZOS)
    im.save(f"{outdir}/cyclist_{i}.png")
    frames.append(im)

# preview montage
mont = Image.new("RGBA",(frames[0].width*FRAMES, frames[0].height),(40,44,52,255))
for i,f in enumerate(frames):
    mont.paste(f,(i*f.width,0),f)
mont.save("/home/user/Bike/tools/preview.png")
print("done", frames[0].size, "x", FRAMES, "frames")
