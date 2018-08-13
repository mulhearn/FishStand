#pragma version(1)
#pragma rs java_package_name(edu.ucdavis.crayfis.fishstand)
#pragma rs_fp_relaxed

rs_allocation g_sum;
rs_allocation g_ssq;

void RS_KERNEL add(ushort in, uint32_t x, uint32_t y) {
    uint old_sum = rsGetElementAt_uint(g_sum, x, y);
    rsSetElementAt_uint(g_sum, old_sum + in, x, y);

    ulong old_ssq = rsGetElementAt_ulong(g_ssq, x, y);
    rsSetElementAt_ulong(g_ssq, old_ssq + in*in, x, y);
}