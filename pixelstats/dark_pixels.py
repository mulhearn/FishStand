#!/usr/bin/env python

import sys
from unpack import *
import matplotlib.pyplot as plt
from matplotlib.colors import LogNorm

import argparse

def process(filename, args):
    # plots to show:
    meanvar = True
    border  = True
    pattern = True
    alldark = True

    # load the image geometry from the calibrations:
    try:
        geom = np.load("calib/geometry.npz");
    except:
        print "calib/geometry.npz does not exist.  Use dump_header.py --geometry"
        return
    width  = geom["width"]
    height = geom["height"]

    print "processing file:  ", filename
    try:
        npz = np.load(filename)
    except:
        print "could not process file ", filename, " as .npz file.  Use --raw option?"
        return

    exposure = npz['exposure']
    sens     = npz['sens']
    sum      = npz['sum']
    ssq      = npz['ssq']
    num     = npz['num']
    index = np.arange(num.size)

    empty = (num == 0)
    num[empty]=1
    mean = sum / num
    vari  = ssq / num - mean**2

    max_mean = 3*np.mean(mean)
    max_vari = 3*np.mean(vari)
    nom_slope = max_vari / max_mean;

    A = nom_slope * args.slope
    B = max_mean * args.offset
    
    xcut = np.array([0,max_mean])
    ycut = A*(xcut-B)

    if (not args.skip_prelim):
        plt.hist2d(mean,vari,norm=LogNorm(),bins=[100,100],range=[[0,max_mean],[0,max_vari]])
        plt.plot(xcut,ycut,"-")
        plt.xlabel("mean")
        plt.ylabel("variance")
        plt.savefig("plots/dark_cut.pdf")
        plt.show()

    dark = (mean < max_mean) & (vari < max_vari) & (vari < (A*(mean - B)))
                                
    if (not args.skip_prelim):                  
        plt.hist2d(mean[dark],vari[dark],norm=LogNorm(),bins=[100,100],range=[[0,max_mean],[0,max_vari]])
        plt.plot(xcut,ycut,"-")
        plt.xlabel("mean")
        plt.ylabel("variance")
        plt.savefig("plots/dark_cut_applied.pdf")
        plt.show()

    xpos = index % width
    ypos = index / width

    darkx = xpos[dark]
    darky = ypos[dark]

    xmin = np.min(darkx)
    xmax = np.max(darkx)
    ymin = np.min(darky)
    ymax = np.max(darky)

    print "minimum dark x value:  ", xmin
    print "maximum dark x value:  ", xmax
    print "minimum dark y value:  ", ymin
    print "maximum dark y value:  ", ymax
   
    best   = 500*500
    best_i = args.period[0]
    best_j = args.period[1]

    print "Scanning for the dark pixel period.  This might take awhile:   use --period if you know it."

    if (best_i == 0):
        for i in range(1,100):
            for j in range(1,100):
                if ((i%10)|(j%10)==0):
                    print "checking period x: ", i, " y: ", j                
                cx = darkx % i
                cy = darky % j
                flat = cy*i + cx
                flat = np.unique(flat)
                occ = (flat.size)/float(i*j)            
                if (occ < best):
                    best = occ
                    best_i = i
                    best_j = j

    print "dark pixel pattern has period (x): ", best_i, " (y) ", best_j, "\n";

    cx = darkx % best_i
    cy = darky % best_j
    flat = cy*best_i + cx
    flat = np.unique(flat)
    ux = flat % best_i
    uy = flat / best_i
    
    plt.plot(ux,uy,".", color="blue")
    plt.xlabel("x position")
    plt.ylabel("y position")
    plt.savefig("plots/dark_pattern.pdf")
    plt.show()

    # now build the entire dark pixel map:
    full  = np.arange(width*height)
    fullx = full % width
    fully = full / width
    mapf = fullx%best_i + (fully%best_j)*best_i
    all_dark = np.in1d(mapf,flat)
    # future python:
    #all_dark = np.isin(mapf,flat)

    # remove borders:
    all_dark = all_dark & (fullx>=xmin) & (fully>=ymin) & (fullx<=xmax) & (fully<=ymax)

    if (args.commit):
        print "saving dark pixel map to calibration directory."
        np.save("calib/all_dark.npy", all_dark)

    plt.subplot(2,1,1)
    plt.hist2d(mean[(all_dark==False)],vari[(all_dark==False)],norm=LogNorm(),bins=[100,100],range=[[0,max_mean],[0,max_vari]])
    plt.xlabel("mean")
    plt.ylabel("variance")
    plt.subplot(2,1,2)
    plt.hist2d(mean[(all_dark==True)],vari[(all_dark==True)],norm=LogNorm(),bins=[100,100],range=[[0,max_mean],[0,max_vari]])
    plt.xlabel("mean")
    plt.ylabel("variance")
    plt.savefig("plots/dark_final.pdf")
    plt.show()

    return 

    #
    # investigate dark pixels with high variance:
    # 

    strange = all_dark & (vari > 7)

    plt.plot(fullx[strange],fully[strange],"o")
    plt.xlabel("x")
    plt.ylabel("y")
    plt.show();


    
if __name__ == "__main__":
    example_text = '''examples:

    ./dark_pixels.py data/combined/dark_50000.npz'''
    
    parser = argparse.ArgumentParser(description='Combine multiple pixelstats data files.', epilog=example_text,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('file', metavar='FILE', nargs=1, help='file to process')
    parser.add_argument('--skip_prelim',action="store_true", help="skip the preliminary plots.")
    parser.add_argument('--slope', metavar='SLOPE',nargs=1, type=float, help='set slope factor to SLOPE (default: %(default).2f)', default=0.6)
    parser.add_argument('--offset', metavar='OFFSET',nargs=1, type=float, help='set offset factor to OFFSET (default: %(default).2f)', default=0.05)
    parser.add_argument('--commit',action="store_true", help="save dark pixel map to calibration directory")
    parser.add_argument('--period', nargs=2, metavar=("X","Y"), type=int,help="specify dark pixel pattern period in x and y", default=[0,0])
    args = parser.parse_args()

    process(args.file[0], args)
