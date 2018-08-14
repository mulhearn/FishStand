#pragma version(1)
#pragma rs java_package_name(edu.ucdavis.crayfis.fishstand)
#pragma rs_fp_relaxed

uint64_t* gHist;
ushort gThresh;
uint32_t* gPixX;
uint32_t* gPixY;
ushort* gPixVal;
uint32_t* gPixN;
uint gMaxN;

void reset() {
    *gPixN = 0;
}

void RS_KERNEL histogram(ushort in, uint32_t x, uint32_t y) {
    volatile uint32_t* addr = &gHist[in];
    rsAtomicInc(addr);

    if (in > gThresh && *gPixN < gMaxN) {
        uint pixN = rsAtomicInc(gPixN);
        *(gPixX+pixN) = x;
        *(gPixY+pixN) = y;
        *(gPixVal+pixN) = in;
    }
}