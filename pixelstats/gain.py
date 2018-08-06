#!/usr/bin/env python

import sys
from unpack import *
import matplotlib.pyplot as plt

means = np.array([])
vars  = np.array([])

chan_offset = 6000
chans  = 1

def process(filename):
    global means, vars, offset, chans

    hsize,version,images,width,height,sens,exposure,num_files,ifile,offset,step_pixels,num_pixels,pixel_start,pixel_end=unpack_header(filename)
    
    index = np.arange(pixel_start, pixel_end) * step_pixels + offset



    mean,var = calc_mean_var(filename)    

   
    means = np.append(means,mean[chan_offset:chan_offset+chans])
    vars  = np.append(vars,var[chan_offset:chan_offset+chans])

   
def analysis():
    global means, vars, offset, chans


    means = np.transpose(means.reshape((-1,chans)))
    vars  = np.transpose(vars.reshape((-1,chans)))

    for i in range(chans):
        plt.plot(means[i],vars[i],"o")

    plt.xlabel("mean")
    plt.ylabel("variance")
    plt.show()

    
if __name__ == "__main__":
    import sys
    for i in range(1,len(sys.argv)):
        filename = str(sys.argv[i])
        print "processing file:  ", filename
        process(filename)
    analysis()

