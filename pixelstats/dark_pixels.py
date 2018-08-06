#!/usr/bin/env python

import sys
from unpack import *
import matplotlib.pyplot as plt

def process(filename):
    # plots to show:
    meanvar = True
    border  = True
    pattern = True
    alldark = True

    # danger hard-coded values:
    iso    = 600
    width  = 5328
    height = 3000

    # from inspection:
    dark_period = 64
    dark_minx   = 44
    dark_miny   = 32
    dark_maxx   = 5280 
    dark_maxy   = 2965 

    # the 2d positions of each pixel:
    mapx = np.arange(width*height) % width
    mapy = np.arange(width*height) / width
    
    dat = np.load(filename);
    print dat.shape    
    num = dat[2]

    # check the sizes agree:
    calc_size = width*height
    print "data size:  ", num.size
    print "calc size:  ", calc_size
    if (num.size != calc_size):
        return

    empty = (dat[2] == 0)
    dat[2][empty]=1
    cmean = dat[0] / dat[2]
    cvari = dat[1] / dat[2] - cmean**2

    A = -0.2
    B = 0.9*iso/200.0  
    dark = (cmean < 3) & (cvari < (A + B*cmean))

    x = np.random.random(cmean.size)
    sup  = (dark == False) & (x < 0.001)

    a = np.array([0,3])
    b = A + B*a

    if (meanvar):
        plt.plot(cmean[sup],cvari[sup],".",color="black",label="normal pixels (1%)")
        plt.plot(cmean[dark],cvari[dark],".",color="blue",label="dark pixels")
        plt.plot(a,b,"--", color="red",label="id cut")

        plt.ylim(0,20)    
        plt.xlim(0,4)   

        plt.xlabel("mean")
        plt.ylabel("variance")
        plt.legend(numpoints=1,loc=0,frameon=False)
        plt.savefig("meanvar.png")
        plt.show()

    dx    = mapx[dark]
    dy    = mapy[dark]

    if (border):
        plt.plot(dx,dy,".", color="blue")
        plt.xlabel("x position")
        plt.ylabel("y position")
        plt.ylim(2900,3000)    
        plt.xlim(5228,5328)   
        plt.show()
    print "x min:  ", np.min(dx)
    print "x max:  ", np.max(dx)
    print "y min:  ", np.min(dy)
    print "y max:  ", np.max(dy)

    px   = dx % dark_period
    py   = dy % dark_period
    flat = px*dark_period + py
    flat = np.unique(flat)
    flat = np.sort(flat)
    ux = flat/dark_period
    uy = flat%dark_period
    uxy = np.column_stack((ux,uy))
    print uxy

    if (pattern):
        plt.plot(ux,uy,".", color="blue")
        plt.xlabel("x position mod 64")
        plt.ylabel("y position mod 64")
        plt.savefig("pattern.pdf")
        plt.show()

    
    mapf = (mapx%dark_period)*dark_period + mapy%dark_period
    all_dark = np.in1d(mapf, flat)
    # remove borders:
    all_dark = all_dark & (mapx>=dark_minx) & (mapy>=dark_miny) & (mapx<=dark_maxx) & (mapy<=dark_maxy)

    # redo the suppressed data to remove all_dark:
    sup  = (all_dark == False) & (x < 0.001)
    if (alldark):
        plt.plot(cmean[sup],cvari[sup],".",color="black",label="normal pixels (1%)")
        plt.plot(cmean[all_dark],cvari[all_dark],".",color="blue",label="dark pixels")    
        plt.ylim(0,20)    
        plt.xlim(0,4)   
        plt.legend(numpoints=1,loc=0,frameon=False)
        plt.savefig("alldark.png")
        plt.show()

    print all_dark
    np.save("calib/all_dark.npy", all_dark)



    
if __name__ == "__main__":
    import sys
    if (len(sys.argv) != 2):
        sys.exit(0)

    filename = str(sys.argv[1])
    print "processing file:  ", filename
    process(filename)

