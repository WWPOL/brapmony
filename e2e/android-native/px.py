#!/usr/bin/env python3
"""Say whether a PNG has any non-black pixels, and what its mean colour is.

The emulator on this host answers every screenshot request with a black image,
which a file listing cannot tell apart from a real capture. This prints
`WxH nonblack N / total mean [r,g,b]`, so a black frame is obvious and a
screenshot is not mistaken for evidence it is not.

Pure stdlib: the point is to run where nothing else is installed.
"""
import sys, zlib, struct
def load(p):
    d=open(p,'rb').read()
    assert d[:8]==b'\x89PNG\r\n\x1a\n'
    i=8; w=h=None; idat=b''; bd=ct=None
    while i<len(d):
        ln=struct.unpack('>I',d[i:i+4])[0]; typ=d[i+4:i+8]; body=d[i+8:i+8+ln]; i+=12+ln
        if typ==b'IHDR': w,h,bd,ct=struct.unpack('>IIBB',body[:10])
        elif typ==b'IDAT': idat+=body
    raw=zlib.decompress(idat)
    bpp={0:1,2:3,3:1,4:2,6:4}[ct]
    stride=w*bpp
    out=bytearray(); prev=bytearray(stride); pos=0
    for y in range(h):
        f=raw[pos]; pos+=1
        line=bytearray(raw[pos:pos+stride]); pos+=stride
        if f==1:
            for x in range(bpp,stride): line[x]=(line[x]+line[x-bpp])&255
        elif f==2:
            for x in range(stride): line[x]=(line[x]+prev[x])&255
        elif f==3:
            for x in range(stride):
                a=line[x-bpp] if x>=bpp else 0
                line[x]=(line[x]+((a+prev[x])>>1))&255
        elif f==4:
            for x in range(stride):
                a=line[x-bpp] if x>=bpp else 0
                b=prev[x]; c=prev[x-bpp] if x>=bpp else 0
                p_=a+b-c; pa=abs(p_-a); pb=abs(p_-b); pc=abs(p_-c)
                pr=a if (pa<=pb and pa<=pc) else (b if pb<=pc else c)
                line[x]=(line[x]+pr)&255
        out+=line; prev=line
    return w,h,bpp,bytes(out)
for p in sys.argv[1:]:
    w,h,bpp,px=load(p)
    n=w*h
    nonblack=sum(1 for i in range(0,len(px),bpp) if px[i] or px[i+1] or px[i+2])
    sums=[0,0,0]
    for i in range(0,len(px),bpp):
        for c in range(3): sums[c]+=px[i+c]
    print(p, f"{w}x{h}", "nonblack", nonblack, "/", n, "mean", [s//n for s in sums])
