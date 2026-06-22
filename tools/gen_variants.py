import math, os
from PIL import Image, ImageDraw, ImageFont

SS = 3
W, H = 240*SS, 270*SS

def L(a,b,t): return (a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t)
def Lc(a,b,t): return tuple(int(a[i]+(b[i]-a[i])*t) for i in range(4))

def cap(d,a,b,width,color):
    d.line([a,b],fill=color,width=int(width))
    r=width/2
    for p in (a,b): d.ellipse([p[0]-r,p[1]-r,p[0]+r,p[1]+r],fill=color)

def ell(d,c,rx,ry,**k): d.ellipse([c[0]-rx,c[1]-ry,c[0]+rx,c[1]+ry],**k)

def vgrad(size, ctop, cbot, y0, y1):
    g=Image.new("RGBA",size,(0,0,0,0)); dd=ImageDraw.Draw(g)
    for y in range(size[1]):
        t=min(1,max(0,(y-y0)/max(1,(y1-y0))))
        dd.line([(0,y),(size[0],y)],fill=Lc(ctop,cbot,t))
    return g

def shade(img, shapefn, ctop, cbot, y0, y1):
    mask=Image.new("L",(W,H),0); md=ImageDraw.Draw(mask); shapefn(md)
    img.paste(vgrad((W,H),ctop,cbot,y0,y1),(0,0),mask)

def draw(style):
    img=Image.new("RGBA",(W,H),(0,0,0,0)); d=ImageDraw.Draw(img)
    cx=W/2; ground=H*0.93; theta=math.pi*0.5
    rearR=H*0.205; wc=(cx,ground-rearR)
    outline = style.get("outline")
    oc=(10,12,16,255); ow=int(SS*2.2)
    # palette
    P=style["pal"]
    JER,JERD,JERH=P["jer"],P["jerd"],P["jerh"]
    SHO,SHOD=P["sho"],P["shod"]
    SK,SKD=P["sk"],P["skd"]
    HEL,HELH=P["hel"],P["helh"]
    SHOE=(16,20,26,255); TIRE=(16,20,28,255); RIM=(150,158,170,255); RIMD=(70,78,90,255); HUB=(182,190,202,255); FR=(22,27,34,255)
    SHADOW=(0,0,0,70)
    aero=style.get("aero",0.0)

    ell(d,(cx,ground+rearR*0.16),rearR*1.0,rearR*0.15,fill=SHADOW)

    bb=(cx,ground-rearR*0.52); crankR=rearR*0.46
    lped=(bb[0]-W*0.018,bb[1]+crankR*math.sin(theta))
    rped=(bb[0]+W*0.018,bb[1]+crankR*math.sin(theta+math.pi))
    hipY=wc[1]-rearR*0.74; hipHalf=W*0.072
    lhip=(cx-hipHalf,hipY); rhip=(cx+hipHalf,hipY)
    torso=rearR*(1.06+aero*0.25); shY=hipY-torso*(1-aero*0.25)
    shHalf=W*(0.125+aero*0.02); lsh=(cx-shHalf,shY); rsh=(cx+shHalf,shY)

    # wheel
    rx,ry=rearR*0.80,rearR
    ell(d,wc,rx,ry,fill=TIRE, outline=oc if outline else None, width=ow if outline else 0)
    if style.get("solid_wheel"):
        ell(d,wc,rx*0.7,ry*0.7,fill=(28,32,40,255))
        ell(d,wc,rx*0.5,ry*0.5,fill=(40,46,56,255))
    else:
        ell(d,wc,rx*0.86,ry*0.86,fill=None,outline=RIMD,width=int(SS*2.2))
        for i in range(12):
            a=i/12*2*math.pi
            ell  # noop
            d.line([wc,(wc[0]+rx*0.82*math.cos(a),wc[1]+ry*0.82*math.sin(a))],fill=(RIM[0],RIM[1],RIM[2],110),width=int(SS*0.8))
    ell(d,wc,rearR*0.085,rearR*0.085,fill=HUB)

    # frame
    cap(d,bb,wc,W*0.022,FR); cap(d,bb,(cx,hipY+rearR*0.02),W*0.026,FR)
    ell(d,bb,crankR*0.30,crankR*0.30,fill=FR)
    cap(d,bb,lped,W*0.013,FR); cap(d,bb,rped,W*0.013,FR)

    def leg(hip,ped,side,calf,thigh):
        lift=bb[1]-ped[1]; mid=L(hip,ped,0.5)
        knee=(mid[0]+side*W*0.05,mid[1]-rearR*0.04-lift*0.34)
        cap(d,hip,knee,rearR*0.20,thigh); cap(d,knee,ped,rearR*0.135,calf)
        ell(d,ped,rearR*0.10,rearR*0.055,fill=SHOE)
    leg(lhip,lped,-1,SKD,SHOD); leg(rhip,rped,1,SK,SHO)

    # shorts seat
    d.polygon([(lhip[0]-hipHalf*0.1,hipY),(rhip[0]+hipHalf*0.1,hipY),(rhip[0],hipY+rearR*0.12),(lhip[0],hipY+rearR*0.12)],fill=SHO)

    barY=shY+torso*0.42
    cap(d,(cx-W*0.14,barY),(cx+W*0.14,barY-H*0.01),W*0.012,FR)

    # torso
    torso_pts=[lhip,lsh,(cx,shY-torso*0.06),rsh,rhip]
    d.polygon(torso_pts,fill=JER, outline=oc if outline else None, width=ow if outline else 0)
    if style.get("shading"):
        shade(img, lambda md: md.polygon(torso_pts,fill=255), JERH, JERD, int(shY-torso*0.1), int(hipY))
    else:
        d.polygon([(cx+shHalf*0.4,shY+torso*0.04),(rsh[0],shY),rhip,(cx+hipHalf*0.4,hipY)],fill=JERD)
        cap(d,lsh,lhip,W*0.012,JERH)
    cap(d,(cx,hipY),(cx,shY+torso*0.1),W*0.012,JERD)
    cap(d,lhip,rhip,W*0.016,JERD)

    # arms reach to bars
    def arm(sh,hand,col):
        elb=(L(sh,hand,0.55)[0],L(sh,hand,0.55)[1]+rearR*0.06)
        cap(d,sh,elb,W*0.05,JERD); cap(d,elb,hand,W*0.04,col)
        ell(d,hand,W*0.024,W*0.024,fill=SHOE)
    arm(lsh,(cx-W*0.085,barY+torso*0.04),SKD); arm(rsh,(cx+W*0.085,barY+torso*0.04),SK)

    # head + helmet
    neck=(cx,shY+torso*0.02); headCy=shY-torso*(0.22-aero*0.08); headW=W*0.115
    cap(d,neck,(neck[0],headCy+headW*0.3),W*0.05,SKD)
    if style.get("tt_helmet"):
        ell(d,(neck[0],headCy),headW*0.6,headW*0.66,fill=HEL, outline=oc if outline else None, width=ow if outline else 0)
        d.polygon([(neck[0]-headW*0.3,headCy),(neck[0]+headW*0.3,headCy),(neck[0],headCy+headW*1.5)],fill=HEL)
    else:
        ell(d,(neck[0],headCy),headW*0.62,headW*0.7,fill=HEL, outline=oc if outline else None, width=ow if outline else 0)
        d.polygon([(neck[0]-headW*0.5,headCy+headW*0.1),(neck[0]+headW*0.5,headCy+headW*0.1),(neck[0],headCy+headW*0.85)],fill=HEL)
    ell(d,(neck[0]-headW*0.18,headCy-headW*0.18),headW*0.22,headW*0.26,fill=HELH)
    cap(d,(neck[0],headCy-headW*0.4),(neck[0],headCy+headW*0.4),W*0.012,(12,15,20,255))

    return img.resize((W//SS,H//SS),Image.LANCZOS)

# palettes
blue=dict(jer=(76,130,230,255),jerd=(40,82,160,255),jerh=(120,165,240,255),
          sho=(212,62,46,255),shod=(150,40,30,255),sk=(233,182,142,255),skd=(196,138,98,255),
          hel=(30,36,44,255),helh=(74,86,102,255))
teal=dict(jer=(28,178,170,255),jerd=(16,120,116,255),jerh=(90,215,206,255),
          sho=(30,38,52,255),shod=(18,24,34,255),sk=(233,182,142,255),skd=(196,138,98,255),
          hel=(245,200,40,255),helh=(255,225,120,255))
red=dict(jer=(216,52,52,255),jerd=(150,30,30,255),jerh=(245,110,100,255),
         sho=(28,32,40,255),shod=(16,20,26,255),sk=(233,182,142,255),skd=(196,138,98,255),
         hel=(245,245,250,255),helh=(255,255,255,255))

variants={
 "A_bold_blue":   dict(pal=blue),
 "B_shaded_blue": dict(pal=blue, shading=True),
 "C_outline":     dict(pal=blue, outline=True),
 "D_minimal":     dict(pal=teal, solid_wheel=True),
 "E_aero_tuck":   dict(pal=red, shading=True, aero=1.0, tt_helmet=True),
 "F_shaded_teal": dict(pal=teal, shading=True),
}

outdir="/home/user/Bike/tools/variants"; os.makedirs(outdir,exist_ok=True)
imgs={}
for name,st in variants.items():
    im=draw(st); im.save(f"{outdir}/{name}.png"); imgs[name]=im

# labeled montage 3x2
cols=3; rows=2; cw=imgs["A_bold_blue"].width+20; ch=imgs["A_bold_blue"].height+34
mont=Image.new("RGBA",(cw*cols, ch*rows),(38,42,50,255)); md=ImageDraw.Draw(mont)
try: font=ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",20)
except: font=ImageFont.load_default()
for i,(name,im) in enumerate(imgs.items()):
    r,c=divmod(i,cols); x=c*cw+10; y=r*ch+28
    md.text((x, r*ch+6), name, fill=(235,238,245,255), font=font)
    mont.paste(im,(x,y),im)
mont.save("/home/user/Bike/tools/variants_sheet.png")
print("variants:", list(imgs.keys()))
