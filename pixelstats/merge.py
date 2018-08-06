#!/usr/bin/env python

import sys
from unpack import *
import matplotlib.pyplot as plt


# recorded from header:
pixels       = 0
pixel_step   = 0

#
# Data from files are considered consistent if they have the same offset, starting pixel, exposure setting,
# and the mean across all channels is with 10%.
#
# These arrays are used to track these parameters during the initial sorting:
#
file_count   = 0
run_offset   = np.array([])
run_start    = np.array([])
run_exposure = np.array([])
run_mean     = np.array([])

#
# The raw data from consistent files are collated at the same index for later processing:
#

raw_cnt      = []
raw_sum      = []
raw_ssq      = []

# Before merging the data, pulls from channel means are calculated to ensure the runs really are consistent:

mean_pull    = np.array([])

# Means and variances are calculated for consistent runs, and collated for each channel
first_pixel        = 5000  # for quick tests where every pixel is not needed
last_pixel         = 6000  # (set to zero for all pixels)
chan_mean          = []
chan_var           = []
chan_count         = np.array([])

#
# This is the initial sort, process each file and append the raw data:
#

def process(filename):
    global file_count,run_offset, run_start, run_exposure, run_mean, raw_cnt, raw_sum, raw_ssq, pixels, last_pixel, first_pixel, chan_mean, chan_var, chan_count, pixel_step

    file_count = file_count + 1

    hsize,version,images,width,height,sens,exposure,num_files,ifile,offset,step_pixels,num_pixels,pixel_start,pixel_end=unpack_header(filename)


    if (pixels == 0):
        pixels = width*height
        pixel_step = step_pixels
        if (last_pixel == 0):
            last_pixel = pixels
        saved_pixels = last_pixel - first_pixel
        chan_mean = [np.array([]) for i in range(saved_pixels)]
        chan_var  = [np.array([]) for i in range(saved_pixels)]
        chan_count = np.zeros(saved_pixels)


    if (pixels != width*height):
        print "ERROR:  data inconsistent image size in data files."
        exit(0)

    version,header,sum,ssq = unpack_all(filename)     
    mean = np.mean(sum.astype(np.float32)/(images))
    # print "exposure:  ", exposure, " mean:  ", mean
    # print "pixels:  ", pixel_end - pixel_start

    match = -1

    cands = np.where(run_exposure==exposure)[0]
    cands = np.intersect1d(cands, np.where(run_offset==offset)[0])
    cands = np.intersect1d(cands, np.where(run_start==pixel_start)[0])

    for i in cands:
        diff = np.abs(mean - run_mean[i])        
        if (diff < (run_mean[i]*0.1)):
            # print "found match..."
            match = i
    
    if (match == -1):
        match = len(raw_cnt)
        run_offset   = np.append(run_offset, offset)
        run_start    = np.append(run_start, pixel_start)
        run_exposure = np.append(run_exposure, exposure)
        run_mean     = np.append(run_mean, mean)
        raw_cnt.append(np.array([]))
        raw_sum.append(np.array([]))
        raw_ssq.append(np.array([]))

    raw_cnt[match] = np.append(raw_cnt[match], images)
    raw_sum[match] = np.append(raw_sum[match], sum)
    raw_ssq[match] = np.append(raw_ssq[match], ssq)


#
# This is the merging of data from consistent runs:
#
#  - the pull on the mean is calculate to confirm consistency of the runs
#  - consistent data is merged and the mean and variance is recorded for each channel
#

def analyze(i):
    global run_offset, run_exposure, run_mean, raw_cnt, raw_sum, raw_ssq, mean_pull, chan_mean, chan_var, chan_count
    if (i > len(raw_cnt)):
        return
    runs = raw_cnt[i].size
    
    if (runs < 2):
        return

    print "further analyzing group ", i
    print "mean first run:  ", run_mean[i]
    print "runs:  ", runs  
    
    raw_sum[i] = raw_sum[i].reshape((runs,-1))
    raw_ssq[i] = raw_ssq[i].reshape((runs,-1))

    cmeans = np.sum(raw_sum[i], axis=0)/np.sum(raw_cnt[i])
    cvars  = np.sum(raw_ssq[i], axis=0)/np.sum(raw_cnt[i]) - cmeans**2

    # calculate means and variances for each channel and run:
    counts = raw_cnt[i][:,None]
    rmeans = raw_sum[i] / counts
    rvars  = raw_ssq[i] / counts  - rmeans**2
    # calculate the statistical uncertainty on the mean:
    runcs  = np.sqrt(rvars / counts)

    # calcualte means a few different ways (consistent if events have same number of events)
    #new_mean = np.mean(cmeans)
    #print "mean all runs (/image):  ", new_mean
    #new_mean - np.mean(rmeans)
    #print "mean all runs:  (/run)", new_mean

    pull = (rmeans - cmeans[None,:]) / runcs
    print "mean pull:  ", np.mean(pull)
    print "rms pull:   ", np.sqrt(np.var(pull))
    mean_pull = np.append(mean_pull, pull )

    for ipixel in range(cmeans.size):
        index = int(run_offset[i] + (run_start[i] + ipixel) * pixel_step)
        if ((index >= first_pixel) and (index < last_pixel)):
            short_index = index - first_pixel
            chan_mean[short_index] = np.append(chan_mean[short_index],cmeans[ipixel])
            chan_var[short_index]  = np.append(chan_var[short_index],cvars[ipixel])
            chan_count[short_index] += 1



def make_plots():
    global mean_pull

    plot_pull = True
    plot_gain = True

    if (plot_pull):
        print "mean pull:  ", np.mean(mean_pull)
        print "rms pull:   ", np.sqrt(np.var(mean_pull))
        h,bins = np.histogram(np.clip(mean_pull,-5,5), bins=100, range=(-5,5))
        err = h**0.5
        cbins = 0.5*(bins[:-1] + bins[1:])
        plt.errorbar(cbins,h,yerr=err,color="black",fmt="o")    
        plt.ylabel("pixels")
        plt.xlabel("pull of mean")
        plt.savefig("hpull.pdf")
        plt.show()

    if (plot_gain):
        nonzero = np.nonzero(chan_count)[0]
        print nonzero
        plotted = 0
        for i in nonzero:
            plotted += 1
            mean = chan_mean[i]
            var  = chan_var[i]
            perm = np.argsort(mean)
            mean = mean[perm]
            var  = var[perm]
            plt.plot(mean,var,"o-")
            plt.xlabel("mean")
            plt.ylabel("variance")
            plt.xlim(0,500)
            plt.ylim(0,2000)
            plt.show()
            if (plotted > 5):
                break

           
def analysis():
    print "done analyzing ", file_count, " files.\n"
    print run_offset
    print run_start
    print run_exposure
    print run_mean    

    for i in range(len(raw_cnt)):
        analyze(i)

    make_plots()
    


    
if __name__ == "__main__":
    import sys
    for i in range(1,len(sys.argv)):
        filename = str(sys.argv[i])
        print "processing file:  ", filename
        process(filename)
    analysis()

