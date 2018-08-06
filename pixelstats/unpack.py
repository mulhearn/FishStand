import numpy as np

def unpack_all(filename):
    with open(filename) as f:
        hsize       = np.fromfile(f,dtype=">i4",count=1)[0]
        version     = np.fromfile(f,dtype=">i4",count=1)[0]
        header      = np.fromfile(f,dtype=">i4",count=hsize)
        num         = (header[11] - header[10])
        #print "reading data from ", num, " pixels."
        sum         = np.fromfile(f,dtype=">i8",count=num)
        ssq         = np.fromfile(f,dtype=">i8",count=num)
        #print "num sum:  ", len(sum)
        #print "num ssq:  ", len(ssq)
        if ((num != len(ssq)) or (num != len(sum))):
            print "data format error... exiting."
            exit(0)

        return version,header,sum,ssq

def unpack_header(filename, show=0):    
    version,header,sum,ssq = unpack_all(filename)

    hsize       = header.size

    images      = header[0]
    num_files   = header[1]
    ifile       = header[2]
    width       = header[3]
    height      = header[4]
    sens        = header[5]
    exposure    = header[6]
    step_pixels = header[7]
    offset      = header[8]
    num_pixels  = header[9]
    pixel_start = header[10]
    pixel_end   = header[11]

    if (show):
        print "file:  ", filename
        print "additional header size:         ", hsize
        print "version:                        ", version
        print "images:                         ", images
        print "width:                          ", width
        print "height:                         ", height
        print "sensitivity:                    ", sens
        print "exposure:                       ", exposure
        print "num files:                      ", num_files
        print "file index:                     ", ifile
        print "offset:                         ", offset
        print "step_pixels:                    ", step_pixels
        print "num_pixels:                     ", num_pixels
        print "pixel_start:                    ", pixel_start
        print "pixel_end:                      ", pixel_end
    return hsize,version,images,width,height,sens,exposure,num_files,ifile,offset,step_pixels,num_pixels,pixel_start,pixel_end

def calc_mean_var(filename):
    version,header,sum,ssq= unpack_all(filename)
    images      = header[0]
    cmean = sum.astype(np.float32)/(images)
    cvar  = (ssq.astype(np.float32)/(images) - cmean**2)
    return cmean,cvar

