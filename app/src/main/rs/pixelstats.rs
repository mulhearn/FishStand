#pragma version(1)
#pragma rs java_package_name(edu.ucdavis.crayfis.fishstand)
#pragma rs_fp_relaxed

rs_allocation g_sum;
rs_allocation g_ssq;
rs_allocation g_max;
rs_allocation g_second;

void RS_KERNEL add_RAW(ushort raw, uint32_t x, uint32_t y) {
    uint in = (uint) raw;

    uint old_sum = rsGetElementAt_uint(g_sum, x, y);
    rsSetElementAt_uint(g_sum, old_sum + in, x, y);

    ulong old_ssq = rsGetElementAt_ulong(g_ssq, x, y);
    rsSetElementAt_ulong(g_ssq, old_ssq + in*in, x, y);

    ushort old_max = rsGetElementAt_ushort(g_max, x, y);
    ushort second_max = rsGetElementAt_ushort(g_second, x, y);
    if(raw > old_max) {
        rsSetElementAt_ushort(g_max, raw, x, y);
        rsSetElementAt_ushort(g_second, old_max, x, y);
    } else if(raw > second_max) {
        rsSetElementAt_ushort(g_second, raw, x, y);
    }
}

void RS_KERNEL add_YUV(uchar ychannel, uint32_t x, uint32_t y) {
    uint in = (uint) ychannel;

    uint old_sum = rsGetElementAt_uint(g_sum, x, y);
    rsSetElementAt_uint(g_sum, old_sum + in, x, y);

    ulong old_ssq = rsGetElementAt_ulong(g_ssq, x, y);
    rsSetElementAt_ulong(g_ssq, old_ssq + in*in, x, y);

    ushort old_max = rsGetElementAt_ushort(g_max, x, y);
    ushort second_max = rsGetElementAt_ushort(g_second, x, y);
    if(ychannel > old_max) {
        rsSetElementAt_ushort(g_max, (ushort)ychannel, x, y);
        rsSetElementAt_ushort(g_second, (ushort)old_max, x, y);
    } else if(ychannel > second_max) {
        rsSetElementAt_ushort(g_second, (ushort)ychannel, x, y);
    }
}