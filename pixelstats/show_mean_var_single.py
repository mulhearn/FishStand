#!/usr/bin/env python

import sys
from unpack import *
import matplotlib.pyplot as plt

def process(filename,index):
    unpack_header(filename,show=1)
    
    mean,var = calc_mean_var(filename)    

    print "mean of means:  ",     np.mean(mean)
    print "mean of variances:  ", np.mean(var)

    isox = np.arange(0., 1050, 10.0)
    isoy = isox*2.72 # projected ISO 640 line from gain studies
    nsyx = np.arange(0., 40, 2)
    nsyy = 3*nsyx*nsyx
    
    plt.close()
    plt.plot(mean,var,".",color="black")
    plt.plot(isox,isoy,"-",color="blue")
    plt.plot(nsyx,nsyy,"-",color="red")
    
    #plt.ylim(0,100000)    
    #plt.xlim(0,1050)   

    #plt.ylim(0,800)    
    #plt.xlim(0,200)   

    plt.ylim(0,200)    
    plt.xlim(0,20)   

    plt.xlabel("mean")
    plt.ylabel("variance")
    plt.show()



    h,bins = np.histogram(np.clip(mean,0,10), bins=100, range=(0,10))
    err = h**0.5
    cbins = 0.5*(bins[:-1] + bins[1:])
    plt.errorbar(cbins,h,yerr=err,color="black",fmt="o")
    plt.ylim(1.0,1E7)
    plt.ylabel("pixels")
    plt.xlabel("mean")
    plt.yscale('log')
    #plt.savefig("hmean.pdf")
    plt.show()

    h,bins = np.histogram(np.clip(var,0,100), bins=100, range=(0,100))
    err = h**0.5
    cbins = 0.5*(bins[:-1] + bins[1:])
    plt.errorbar(cbins,h,yerr=err,color="black",fmt="o")
    plt.ylim(1.0,1E7)
    plt.ylabel("pixels")
    plt.xlabel("variance")
    plt.yscale('log')
    #plt.savefig("hmean.pdf")
    plt.show()

   

    
if __name__ == "__main__":
    import sys
    for i in range(1,len(sys.argv)):
        filename = str(sys.argv[i])
        print "processing file:  ", filename
        process(filename,i)

