#!/usr/bin/env python

import sys
from unpack import *
import matplotlib.pyplot as plt

def analysis():
    # danger hard-coded values:
    width  = 5328
    height = 3000

    gain_fit = np.load("calib/gain.npz");
    gain = gain_fit["gain"]

    dark = np.load("calib/all_dark.npy");

    if (gain.size != width*height):
        print "inconsistent number of pixels in gain results."
        exit(0)

    if (dark.size != width*height):
        print "inconsistent number of pixels in dark pixel results."
        exit(0)

    index = np.arange(0,width*height)
    xpos = index % width
    ypos = index / width

    # drop dark pixels:
    keep = (dark == False)
    gain = gain[keep]
    xpos = xpos[keep]
    ypos = ypos[keep]
    
    # now restrict to just non-empty pixels:
    keep = np.nonzero(gain)[0]
    gain = gain[keep]
    xpos = xpos[keep]
    ypos = ypos[keep]

    # with finite values:
    keep = np.isfinite(gain)
    gain = gain[keep]
    xpos = xpos[keep]
    ypos = ypos[keep]

    bins = 500
    
    G  = np.zeros(bins*bins)
    S  = np.zeros(bins*bins)
    for i in range(gain.size):
        xc = bins * xpos[i] / width
        yc = bins * ypos[i] / height
        gc = gain[i]
        ic = xc + yc*bins
        G[ic] += gc
        S[ic] += 1.0
    nz = np.nonzero(G)[0]

    G[nz] = G[nz]/S[nz]
    
    G = G.reshape((bins,bins))
    print G
    plt.imshow(G, vmin=2, vmax=5)
    plt.colorbar()
    plt.savefig("plots/shading.png")
    plt.show()
   
   
if __name__ == "__main__":

    analysis()


