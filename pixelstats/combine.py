#! /usr/bin/env python

import argparse
import sys
import re

import numpy as np

from unpack import *

OUTFILE = "combined.npz"

CHECK_EXPOSURE = 0
CHECK_MIN = 0
CHECK_MAX = 0

PIXELS   = 0
SENS     = 0
EXPOSURE = 0

SUM = np.array([])
SSQ = np.array([])
NUM = np.array([])

def process(filename):
    global PIXELS, SENS, EXPOSURE, SUM, SSQ, NUM

    if (CHECK_MAX > 0):
        match = re.search(r'run_([\d]+)_', filename)
        run = int(match.group(1))
        if ((run < CHECK_MIN) or (run > CHECK_MAX)):
            return

    version,header,sum,ssq = unpack_all(filename)

    exposure = interpret_header(header,"exposure")
    sens     = interpret_header(header,"sens")
    width    = interpret_header(header,"width")
    height   = interpret_header(header,"height")
    
    pixel_start = interpret_header(header,"pixel_start")
    pixel_end   = interpret_header(header,"pixel_end")
    offset      = interpret_header(header,"offset")
    step_pixels = interpret_header(header,"step_pixels")

    images = interpret_header(header,"images")
    
    
    if ((CHECK_EXPOSURE > 0) and (exposure != CHECK_EXPOSURE)):
        #print "(skipping file ", filename, " with exposure ", exposure, ")"
        return


    print "processing file:  ", filename


    # take parameters from first file:
    if (PIXELS  == 0):
        PIXELS = width*height
        SENS = sens
        EXPOSURE = exposure
        SUM = np.zeros(PIXELS)
        SSQ = np.zeros(PIXELS)
        NUM = np.zeros(PIXELS)

    if ((exposure!=EXPOSURE) or (sens!=SENS)):
        print "ERROR: inconsistent run parameters in file ", filename
        unpack_header(filename, show=1)
        exit(0)



    for i in range(pixel_start, pixel_end):
        full_index = offset + i*step_pixels
        flat_index = i - pixel_start
        SUM[full_index] += sum[flat_index]
        SSQ[full_index] += ssq[flat_index]
        NUM[full_index] += images

def post():
    print "saving combined data to", OUTFILE

    #dat = (csum,cssq,cnum)
    np.savez(OUTFILE, sum=SUM, ssq=SSQ, num=NUM, exposure=EXPOSURE, sens=SENS)


if __name__ == "__main__":
    example_text = '''example:

    ./combine.py --exposure=50000 dark/*.dat --range 1242 1310 --out=dark_50000.npz'''

    parser = argparse.ArgumentParser(description='Combine multiple pixelstats data files.', epilog=example_text,formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('files', metavar='FILE', nargs='+',
                    help='file to process')
    parser.add_argument('--exposure',type=int,help="use only runs with this exposure setting")
    parser.add_argument('--out',help="output filename")    
    parser.add_argument('--range', nargs=2, metavar=("FIRST","LAST"), type=int,help="only use runs where FIRST <= run number <= LAST")
    args = parser.parse_args()
    
    if (args.range != None):
        CHECK_MIN = args.range[0]
        CHECK_MAX = args.range[1]
        print "using only runs with run number from ", CHECK_MIN, " to ", CHECK_MAX, " (inclusive)."

    if ((args.exposure != None) and (args.exposure > 0)):
        CHECK_EXPOSURE = args.exposure
        print "using only runs with exposure ", CHECK_EXPOSURE

    if (args.out != None):
        OUTFILE = args.out    

    for filename in args.files:
        process(filename)
    post()
        
