package sternhalma.tornado;

import uk.ac.manchester.tornado.api.TornadoProfilerResult;

/**
 * What one execution of a TornadoVM plan cost, from the TornadoVM profiler.
 * The plan must run with {@code withProfiler(ProfilerMode.SILENT)}, otherwise all values are 0 or -1.
 *
 * @param totalNanos    wall time of the execution as TornadoVM measured it
 * @param compileNanos  time spent compiling kernels; 0 once the kernels are compiled
 * @param kernelNanos   time the kernels ran on the device; above 0 only if they really ran there
 * @param bytesToDevice bytes copied from the host to the device
 * @param bytesToHost   bytes copied from the device to the host
 */
public record ExecutionProfile(long totalNanos, long compileNanos, long kernelNanos, long bytesToDevice,
        long bytesToHost) {

    static ExecutionProfile of(TornadoProfilerResult result) {
        return new ExecutionProfile(result.getTotalTime(), result.getCompileTime(), result.getDeviceKernelTime(),
                result.getTotalBytesCopyIn(), result.getTotalBytesCopyOut());
    }
}
