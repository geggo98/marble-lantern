// The second build of simd_net.cpp: the same source, -ffp-contract=off (the project default, no per-file flag), in the
// namespace simd_nofma. It is the "without FMA" row of the comparison.
#define SH_SIMD_NS simd_nofma
#include "simd_net.cpp"
