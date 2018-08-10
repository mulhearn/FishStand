#!/usr/bin/env python

#
# gain.py:  combines multiple files under different exposure and light conditions to determine the best fit gain for every pixel
#

import argparse
import sys
from unpack import *
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker

#  Associated with each pixel is a sequences of means and variances, arising from runs under different conditions.
pixels              = 0
first_pixel         = 0
last_pixel          = 0
chan_means          = []
chan_vars           = []

# For each channel, a gain and intercept is calculated:
chan_intercept     = np.array([])
chan_gain          = np.array([])


def init(filename, args):
    global pixels,first_pixel, last_pixel, chan_means, chan_vars, chan_intercept, chan_gain
    
    version,header,sum,ssq = unpack_all(filename)     
    width       = interpret_header(header, "width")
    height      = interpret_header(header, "height")        
    pixels = width*height
    first_pixel = args.pixel_range[0]
    last_pixel  = args.pixel_range[1] 
    if ((last_pixel <= 0) or (last_pixel > pixels)):
        last_pixel = pixels
    if (first_pixel < 0):
        first_pixel = 0
    saved_pixels = last_pixel - first_pixel
    chan_means = [np.array([]) for i in range(saved_pixels)]
    chan_vars  = [np.array([]) for i in range(saved_pixels)]
    chan_intercept = np.zeros(saved_pixels)
    chan_gain = np.zeros(saved_pixels)

def process(filename, args):
    print "processing file ", filename

    print "first_pixel:  ", first_pixel
    print "last_pixel:   ", last_pixel

    version,header,sum,ssq = unpack_all(filename)     

    index = get_pixel_indices(header)
    images = interpret_header(header, "images")
    keep = (index >= first_pixel) & (index< last_pixel)
    sum   = sum[keep]
    ssq   = ssq[keep]
    index = index[keep]
    num   = np.full(index.size, images)

    cmean = sum / num
    cvari = ssq / num - cmean**2

    for i in range(index.size):
        short_index = index[i] - first_pixel
        chan_means[short_index] = np.append(chan_means[short_index],cmean[i])
        chan_vars[short_index] = np.append(chan_vars[short_index],cvari[i])

    return 

# Best-fit line, assuming constant fractional uncertainty in the y value.
def fit_line(x,y):
    ex  = np.sum(x/np.square(y))
    ey  = np.sum(np.reciprocal(y))
    exx = np.sum(np.square(x)/np.square(y))
    exy = np.sum(x/y)
    ew  = np.sum(np.square(np.reciprocal(y)))
    denom = exx*ew - ex*ex
    a = 0
    b = 0
    if (denom != 0):
        a = (exy*ew - ex*ey) / denom   # slope
        b = (exx*ey - exy*ex) / denom  # intercept
    return a, b

def fit_gain(args):
    global chan_gain, chan_intercept
    nonzero = np.nonzero([chan_means[i].size for i in range(len(chan_means))])[0]
    for i in nonzero:
        if (chan_means[i].size <= 1):
            continue
        chan_gain[i],chan_intercept[i] = fit_line(chan_means[i], chan_vars[i])

    if (args.commit):
        print "saving fitted gain results"
        full_gain      = np.zeros(pixels)
        full_intercept = np.zeros(pixels)
        full_gain[first_pixel:last_pixel] = chan_gain
        full_intercept[first_pixel:last_pixel] = chan_intercept
        np.savez("calib/gain.npz", gain=full_gain, intercept=full_intercept)

    return


def make_plots(args):
    xmax = args.max_mean
    ymax = args.max_var
    plt.close()
    f, axes = plt.subplots(3, 3, sharex='col', sharey='row')
    axes = axes.flatten()

    nonzero = np.nonzero([chan_means[i].size for i in range(len(chan_means))])[0]
    plotted = 0
    for i in nonzero:
        mean = chan_means[i]
        var  = chan_vars[i]
        perm = np.argsort(mean)
        mean = mean[perm]
        var  = var[perm]

        a = chan_gain[i]
        b = chan_intercept[i]
        #print a, b



        fx = np.array([0, xmax])
        fy = a*fx + b

        axes[plotted].plot(fx,fy,"--k")
        axes[plotted].plot(mean,var,"ob")
        axes[plotted].set_xlim(0,xmax)        
        axes[plotted].set_ylim(0,ymax)    
        axes[plotted].xaxis.set_major_locator(ticker.LinearLocator(3))
        axes[plotted].xaxis.set_minor_locator(ticker.LinearLocator(xmax/20+1))
        axes[plotted].yaxis.set_major_locator(ticker.LinearLocator(3))
        axes[plotted].yaxis.set_minor_locator(ticker.LinearLocator(ymax/100+1))
        axes[plotted].text(0.3*xmax,0.8*ymax,"pixel "+str(first_pixel+i),horizontalalignment='center')

        plotted += 1
        if (plotted == 9):
            break
    axes[3].set_ylabel("variance")
    axes[7].set_xlabel("mean")
    plt.savefig("plots/gain.pdf")
    plt.show()
    return

def analysis(args):
    fit_gain(args)
    make_plots(args)
        
if __name__ == "__main__":
    example_text = '''examples:

    ...'''
    
    parser = argparse.ArgumentParser(description='Fit gain of each pixel from a series of runs at different exposures and light levels', epilog=example_text,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('files', metavar='FILE', nargs='+', help='file to process')
    parser.add_argument('--commit',action="store_true", help="save calibration results to calibration directory")
    parser.add_argument('--pixel_range',type=int,nargs=2, metavar=("MIN","MAX"),help="Only evalulate pixels with MIN <= index < MAX", default=[0,0])
    parser.add_argument('--max_var',type=float,metavar="X",help="maximum variance in plots",default=800)
    parser.add_argument('--max_mean',type=float,metavar="Y",help="maximum mean in plots",default=200)
    args = parser.parse_args()

    init(args.files[0], args)

    for filename in args.files:
        process(filename, args)

    analysis(args)

