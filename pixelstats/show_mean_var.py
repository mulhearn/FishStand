#!/usr/bin/env python

import sys
from unpack import *
import matplotlib.pyplot as plt

def process(filename):
    # plots to show:
    meanvar = False
    mvzoom  = False
    hmean   = True
    hvari  = True

    # danger hard-coded values:
    iso    = 600
    width  = 5328
    height = 3000

    dat = np.load(filename);
    print dat.shape    
    num = dat[2]

    # check the sizes agree:
    calc_size = width*height
    print "data size:  ", num.size
    print "calc size:  ", calc_size
    if (num.size != calc_size):
        return

    # load the dark pixel mask:
    all_dark = np.load("calib/all_dark.npy")
    print "dark map size:  ", all_dark.size
    if (all_dark.size != calc_size):
        return

    empty = (dat[2] == 0)
    dat[2][empty]=1
    cmean = dat[0] / dat[2]
    cvari = dat[1] / dat[2] - cmean**2

    cmean = cmean[(all_dark == False)]
    cvari = cvari[(all_dark == False)]

    x = np.random.random(cmean.size)
    sup  = (x < 0.001)


    if (meanvar):
        plt.plot(cmean[sup],cvari[sup],".",color="black",label="normal pixels (1%)")
        plt.ylim(0,200)    
        plt.xlim(0,50)   

        plt.xlabel("mean")
        plt.ylabel("variance")
        plt.savefig("meanvar.png")
        plt.show()


    if (mvzoom):
        plt.plot(cmean[sup],cvari[sup],".",color="black",label="normal pixels (1%)")
        plt.ylim(0,20)    
        plt.xlim(0,4)   

        plt.xlabel("mean")
        plt.ylabel("variance")
        plt.savefig("mvzoom.png")
        plt.show()

    if (hmean):
        h,bins = np.histogram(np.clip(cmean,0,10), bins=100, range=(0,10))
        err = h**0.5
        cbins = 0.5*(bins[:-1] + bins[1:])
        plt.errorbar(cbins,h,yerr=err,color="black",fmt="o")
        plt.ylim(1.0,1E7)
        plt.ylabel("pixels")
        plt.xlabel("mean")
        plt.yscale('log')
        plt.savefig("hmean.pdf")
        plt.show()

    if (hvari):
        h,bins = np.histogram(np.clip(cvari,0,100), bins=100, range=(0,100))
        err = h**0.5
        cbins = 0.5*(bins[:-1] + bins[1:])
        plt.errorbar(cbins,h,yerr=err,color="black",fmt="o")
        plt.ylim(1.0,1E7)
        plt.ylabel("pixels")
        plt.xlabel("variance")
        plt.yscale('log')
        plt.savefig("hvari.pdf")
        plt.show()


    
    #plt.close()
    #h,bins = np.histogram(np.clip(var,0,100), bins=100, range=(0,100))
    #err = h**0.5
    #cbins = 0.5*(bins[:-1] + bins[1:])
    #plt.errorbar(cbins,h,yerr=err,color="black",fmt="o")
    #plt.ylim(1.0,1E5)
    #plt.xlim(0.0,100.0)
    #plt.ylabel("pixels")
    #plt.xlabel("variance")
    #plt.yscale('log')
    #plt.text(90.0, 12000.0,iso_tag, horizontalalignment='right',color="black", fontsize=18)
    #plt.text(9.0, 11000.0,exp_tag, horizontalalignment='right',color="black", fontsize=18)
    #plt.text(90.0, 6000.0,exp_tag, horizontalalignment='right',color="black", fontsize=18)
    #outfile = "var" + name_tag + ".png"
    #plt.savefig(outfile)
    #plt.show()

    dx    = mapx[dark]
    dy    = mapy[dark]





    
if __name__ == "__main__":
    import sys
    if (len(sys.argv) != 2):
        sys.exit(0)

    filename = str(sys.argv[1])
    print "processing file:  ", filename
    process(filename)

