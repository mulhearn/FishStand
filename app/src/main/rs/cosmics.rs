#pragma version(1)
#pragma rs java_package_name(edu.ucdavis.crayfis.fishstand)
#pragma rs_fp_relaxed

uint64_t* gHist;
ushort gThresh;
uint32_t* gPixX; // these need to be 32-bit, otherwise the addresses get complicated
uint32_t* gPixY;
uint32_t* gPixVal;
uint32_t* gPixN;
uint gMaxN;


void RS_KERNEL histogram(ushort raw, uint32_t x, uint32_t y) {
    volatile uint32_t* addr = &gHist[raw];
    rsAtomicInc(addr);

    if (raw > gThresh && *gPixN < gMaxN) {
        uint pixN = rsAtomicInc(gPixN);
        *(gPixX+pixN) = x;
        *(gPixY+pixN) = y;
        *(gPixVal+pixN) = raw;
    }
}

void RS_KERNEL weighted_histogram(ushort raw, half weight, uint32_t x, uint32_t y) {
    ushort adjusted = (ushort) (raw * weight);

    volatile uint32_t* addr = &gHist[adjusted];
    rsAtomicInc(addr);

    if (adjusted > gThresh && *gPixN < gMaxN) {
        uint pixN = rsAtomicInc(gPixN);
        *(gPixX+pixN) = x;
        *(gPixY+pixN) = y;
        *(gPixVal+pixN) = raw;
    }
}

void reset() {
    *gPixN = 0;
}

half RS_KERNEL setToUnity(half in) {
    return 1;
}