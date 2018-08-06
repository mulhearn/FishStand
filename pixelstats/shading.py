#!/usr/bin/env python

import sys
from unpack import *
import matplotlib.pyplot as plt




def process(filename):
    global means, vars, offset, chans

    hsize,version,images,width,height,sens,exposure,num_files,ifile,offset,step_pixels,num_pixels,pixel_start,pixel_end=unpack_header(filename)
    
    index = np.arange(pixel_start, pixel_end) * step_pixels + offset
    xpos = index % width
    ypos = index / width
    r = (xpos-width/2)**2 + (ypos-height/2)**2
    r = r / float(width**2 + height**2)
    r = 2*np.sqrt(r)
    

    mean,var = calc_mean_var(filename)    
    gain = var / mean


    #good = (mean<3) & (var<15)
    #gain = gain[good]
    #xpos = xpos[good]
    #ypos = ypos[good]


    bins = 100
    
    G  = np.zeros(bins*bins)
    S  = np.zeros(bins*bins)
    for i in range(gain.size):
        xc = bins * xpos[i] / width
        yc = bins * ypos[i] / height
        gc = gain[i]
        ic = xc + yc*bins
        G[ic] += gc
        S[ic] += 1.0
    G = G/S
    

    G = G.reshape((bins,bins))
    print G
    plt.imshow(G, vmin=4, vmax=7)
    plt.colorbar()
    plt.savefig("shading.png")
    plt.show()


    #plt.pcolormesh(XC, YC, G)
    #plt.plot(r, gain,".")
    #plt.ylim(0,20)
    #plt.xlim(-0.2,1.2)
    #plt.show()

   
   
if __name__ == "__main__":

    for i in range(1,len(sys.argv)):
        filename = str(sys.argv[i])
        print "processing file:  ", filename
        process(filename)


