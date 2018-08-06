#! /usr/bin/env python

import sys
from unpack import *

def process(filename,index):
    unpack_header(filename,show=1)

if __name__ == "__main__":
    import sys
    for i in range(1,len(sys.argv)):
        filename = str(sys.argv[i])
        print "processing file:  ", filename
        process(filename,i)

        
