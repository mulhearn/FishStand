#! /usr/bin/env python

import sys

import numpy as np

from unpack import *

total_pixels = 0;
csum = np.array([])
cssq = np.array([])
cnum = np.array([])


def process(filename):
    global total_pixels,csum,cssq,cnum
    hsize,version,images,width,height,sens,exposure,num_files,ifile,offset,step_pixels,num_pixels,pixel_start,pixel_end = unpack_header(filename)
    version,header,sum,ssq = unpack_all(filename)
    
    if (total_pixels == 0):
        total_pixels = width*height
        csum = np.zeros(total_pixels)
        cssq = np.zeros(total_pixels)
        cnum = np.zeros(total_pixels)

    num_pixels = pixel_end - pixel_start
    for i in range(pixel_start, pixel_end):
        full_index = offset + i*step_pixels
        flat_index = i - pixel_start
        csum[full_index] += sum[flat_index]
        cssq[full_index] += ssq[flat_index]
        cnum[full_index] += images

def post():
    global total_pixels
    print "total pixels:  ", total_pixels
    dat = (csum,cssq,cnum)
    np.save("combined.npy", dat)



if __name__ == "__main__":
    import sys
    if (len(sys.argv) != 5):
        sys.exit(0);

    dir = sys.argv[1]
    ra  = int(sys.argv[2])
    rb  = int(sys.argv[3])
    nf  = int(sys.argv[4])
    for i in range(ra,rb):
        for j in range(nf):
            filename = dir + "/run_" + str(i) + "_part_" + str(j) + "_pixelstats.dat"
            print "processing file:  ", filename
            process(filename)
    post()
        
