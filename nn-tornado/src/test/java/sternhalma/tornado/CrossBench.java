package sternhalma.tornado;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sternhalma.nn.Encoder;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.Parameters;
import sternhalma.nn.VectorNetwork;
import sternhalma.nn.VectorSupport;

/**
 * Not a test: the Java side of the cross-language network benchmark (docs/cpp-comparison-plan.md, "Network measurement",
 * WP8). It runs the schedule that the C++ program {@code nn_bench} runs too, on the golden files of
 * {@code :nn:goldenExport}, and prints one TSV line per case in the shared schema.
 *
 * <pre>
 * crossBench --args="--golden cpp/golden --presets small,large --batches 1,16,64,256,512,1024 --impls scalar,vector,tornado"
 *            [--anchors] [--cold] [--no-profiled]
 * </pre>
 *
 * <p><b>Schedule per case</b> (implementation x preset x batch): warm-up of at least 2 s and 10 calls (a CPU case stops the
 * warm-up at 2 s even with fewer calls, because one call is then longer than 0.2 s), then 3 rounds of at least 1 s and at
 * least one call. The wall time of every call is taken with {@code System.nanoTime} around {@code forward}. The input of call
 * c is the golden positions {@code c*n .. c*n+n-1} modulo 1,024, copied into arrays before the case starts (batches that divide
 * 1,024 use 1,024/n distinct slices). {@code evals_per_s} is the median over the rounds of n * calls / round time;
 * {@code round_min} and {@code round_max} are the other two. {@code ms_per_call_median} and {@code ms_p90} are over the calls
 * of all rounds. A CPU case whose single call would take more than 3 s at the rate of the smaller batches is not run (a
 * comment line says so). Each case ends with the check of its last call against the reference: a violation of the bound of
 * {@code Network.agrees} (plus the extra term of the manifest, if any) ends the program with exit code 2 and no line for the
 * case.
 *
 * <p><b>Columns</b>: {@code lang impl device preset weights batch bucket lanes evals_per_s ms_per_call_median ms_p90 round_min
 * round_max copy_in_ms h2d_ms kernel_ms d2h_ms copy_out_ms priors_ms first_call_ms load_1min verified}. A phase that the
 * implementation cannot measure separately is {@code NA}. For TornadoVM {@code kernel_ms} is the "execute" figure of
 * {@link TornadoNetwork#timings()} (host to device, kernels and device to host in one), {@code h2d_ms} and {@code d2h_ms} are
 * {@code NA}; the phase figures are the delta of the totals over the measured rounds divided by the executions in them.
 * {@code first_call_ms} is the first call of the case, which includes the plan build and the kernel compilation of a
 * new bucket. {@code bucket} is the TornadoVM bucket (next power of two), {@code NA} for the CPU paths; {@code lanes} is the
 * Vector API lane count for {@code vector}, else {@code NA}.
 *
 * <p>{@code --anchors} runs only the anchor rows (SMALL and LARGE, batch 256). {@code --cold} runs one forward pass of the
 * first preset, impl and batch and exits; the comment line {@code # cold} gives the JVM uptime when the call returned, for the
 * start-up measurement. After the main table a second, profiled pass of {@link TornadoNetwork#profiled} prints the kernel,
 * write and read times of the profiler as lines starting with {@code #profiled} (the profiler slows the call, so they are not
 * in the main table); {@code --no-profiled} omits it. GC collections during the measured rounds are counted per case in a
 * comment line.
 */
public final class CrossBench {

    private static final long WARMUP_NANOS = 2_000_000_000L;
    private static final int WARMUP_CALLS = 10;
    private static final long ROUND_NANOS = 1_000_000_000L;
    private static final int ROUNDS = 3;
    private static final long CPU_CALL_LIMIT_NANOS = 3_000_000_000L;
    private static final int PROFILED_CALLS = 20;
    private static final String NA = "NA";
    private static final Pattern TIMINGS = Pattern.compile("(\\d+) executions: copy in ([\\d.]+) ms, execute ([\\d.]+) ms, "
            + "copy out ([\\d.]+) ms, host priors ([\\d.]+) ms");
    private static final String COLUMNS = "lang\timpl\tdevice\tpreset\tweights\tbatch\tbucket\tlanes\tevals_per_s"
            + "\tms_per_call_median\tms_p90\tround_min\tround_max\tcopy_in_ms\th2d_ms\tkernel_ms\td2h_ms\tcopy_out_ms"
            + "\tpriors_ms\tfirst_call_ms\tload_1min\tverified";

    private static double sink;

    private CrossBench() {
    }

    /** The command line. */
    private record Options(Path golden, List<String> presets, int[] batches, List<String> impls, boolean anchors,
            boolean cold, boolean profiled) {
    }

    public static void main(String[] args) throws Exception {
        Options o = parse(args);
        GoldenFiles golden = new GoldenFiles(o.golden());
        boolean wantTornado = o.impls().contains("tornado");
        String device = "cpu";
        String deviceName = "none";
        if (wantTornado) {
            TornadoSupport.Status status = TornadoSupport.status();
            if (!status.usable()) {
                System.err.println("TornadoVM is not usable: " + status);
                System.exit(3);
            }
            deviceName = GpuEvaluators.description();
            device = deviceName.replaceAll(".*\\(([A-Za-z]+)\\).*", "$1").toLowerCase(Locale.ROOT);
        }
        printHeader(o, deviceName);
        System.out.println(COLUMNS);
        float[] inputs = golden.inputs();
        int[] batches = o.anchors() ? new int[] {256} : o.batches();
        List<String> presets = o.anchors() ? List.of("small", "large") : o.presets();
        for (String preset : presets) {
            Parameters parameters = golden.weights(preset);
            String weights = preset.equals("gen11") ? "gen11" : "u5";
            String presetName = preset.equals("small") ? "small" : "large";
            float[][] ref = golden.reference(preset, false);
            int players = parameters.config().players();
            for (String impl : o.impls()) {
                double extra = golden.extraRel(impl, preset);
                long tConstruct = System.nanoTime();
                ForwardPass net = switch (impl) {
                    case "scalar" -> new Network(parameters);
                    case "vector" -> new VectorNetwork(parameters);
                    case "tornado" -> new TornadoNetwork(parameters);
                    default -> throw new IllegalArgumentException("unknown impl " + impl);
                };
                double constructMs = (System.nanoTime() - tConstruct) / 1e6;
                try {
                    double rateSoFar = 0;
                    for (int batch : batches) {
                        boolean cpu = !impl.equals("tornado");
                        if (cpu && rateSoFar > 0 && batch / rateSoFar * 1e9 > CPU_CALL_LIMIT_NANOS) {
                            System.out.printf(Locale.ROOT, "# skipped: %s %s %s batch %d (one call would take about %.1f s)%n",
                                    impl, presetName, weights, batch, batch / rateSoFar);
                            continue;
                        }
                        Case c = new Case(impl, cpu ? "cpu" : device, presetName, weights, batch, players, extra, inputs, ref);
                        Result r = c.run(net, o.cold());
                        rateSoFar = r.rateMedian;
                        System.out.println(r.line);
                        if (o.cold()) {
                            System.out.printf(Locale.ROOT, "# cold: construct %.1f ms, first call %.1f ms, JVM uptime %d ms%n",
                                    constructMs, r.firstCallMs, ManagementFactory.getRuntimeMXBean().getUptime());
                            return;
                        }
                    }
                } finally {
                    if (net instanceof TornadoNetwork t) {
                        t.close();
                    }
                }
            }
        }
        if (wantTornado && o.profiled() && !o.anchors()) {
            profiled(golden, presets, batches, inputs);
        }
        System.out.printf(Locale.ROOT, "# checksum %.6g%n", sink);
        System.out.printf(Locale.ROOT, "# load_1min after %.2f%n", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }

    /** One measured case: the schedule, the verification and the line. */
    private static final class Case {
        final String impl;
        final String device;
        final String preset;
        final String weights;
        final int batch;
        final int players;
        final double extra;
        final float[] all;
        final float[][] ref;

        Case(String impl, String device, String preset, String weights, int batch, int players, double extra, float[] all,
                float[][] ref) {
            this.impl = impl;
            this.device = device;
            this.preset = preset;
            this.weights = weights;
            this.batch = batch;
            this.players = players;
            this.extra = extra;
            this.all = all;
            this.ref = ref;
        }

        Result run(ForwardPass net, boolean once) {
            int size = net.config().inputSize();
            int distinct = GoldenFiles.POSITIONS / gcd(GoldenFiles.POSITIONS, batch);
            float[][] slices = new float[distinct][];
            for (int k = 0; k < distinct; k++) {
                float[] s = new float[batch * size];
                for (int b = 0; b < batch; b++) {
                    System.arraycopy(all, (int) (((long) k * batch + b) % GoldenFiles.POSITIONS) * size, s, b * size, size);
                }
                slices[k] = s;
            }
            float[] logits = new float[batch * Encoder.POLICY_SIZE];
            float[] values = new float[batch * players];
            TornadoNetwork tornado = net instanceof TornadoNetwork t ? t : null;
            double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();

            long call = 0;
            long t0 = System.nanoTime();
            net.forward(slices[0], batch, logits, values);
            double firstMs = (System.nanoTime() - t0) / 1e6;
            call++;
            if (once) {
                double frac = verify(logits, values, 0);
                return new Result(line(true, 0, 0, 0, 0, 0, null, firstMs, load, frac), firstMs, 0);
            }
            long start = System.nanoTime();
            while (true) {
                long now = System.nanoTime();
                boolean timeUp = now - start >= WARMUP_NANOS;
                if (timeUp && (tornado == null || call >= WARMUP_CALLS)) {
                    break;
                }
                net.forward(slices[(int) (call % distinct)], batch, logits, values);
                call++;
            }
            double[] before = phases(tornado);
            long gcCount = gcCount();
            long gcMillis = gcMillis();
            double[] rates = new double[ROUNDS];
            long[] times = new long[1024];
            int n = 0;
            for (int r = 0; r < ROUNDS; r++) {
                long begin = System.nanoTime();
                long calls = 0;
                long elapsed;
                do {
                    long a = System.nanoTime();
                    net.forward(slices[(int) (call % distinct)], batch, logits, values);
                    long z = System.nanoTime();
                    call++;
                    calls++;
                    if (n == times.length) {
                        times = Arrays.copyOf(times, n * 2);
                    }
                    times[n++] = z - a;
                    elapsed = z - begin;
                } while (elapsed < ROUND_NANOS);
                rates[r] = calls * (double) batch / (elapsed / 1e9);
            }
            double[] after = phases(tornado);
            long gcN = gcCount() - gcCount;
            long gcMs = gcMillis() - gcMillis;
            int lastSlice = (int) ((call - 1) % distinct);
            double frac = verify(logits, values, (int) (((long) lastSlice * batch) % GoldenFiles.POSITIONS));
            sink += logits[(int) (System.nanoTime() % logits.length)] + values[0];
            long[] sorted = Arrays.copyOf(times, n);
            Arrays.sort(sorted);
            double[] sortedRates = rates.clone();
            Arrays.sort(sortedRates);
            double median = sortedRates[ROUNDS / 2];
            double ms50 = sorted[n / 2] / 1e6;
            double ms90 = sorted[Math.min(n - 1, (int) Math.ceil(0.9 * n) - 1)] / 1e6;
            double[] perCall = null;
            if (tornado != null && after[0] > before[0]) {
                double executions = after[0] - before[0];
                perCall = new double[] {(after[1] - before[1]) / executions, (after[2] - before[2]) / executions,
                        (after[3] - before[3]) / executions, (after[4] - before[4]) / executions};
            }
            System.out.printf(Locale.ROOT, "# gc: %s %s %s batch %d: %d collections, %d ms during the measured rounds%n", impl,
                    preset, weights, batch, gcN, gcMs);
            return new Result(line(false, median, ms50, ms90, sortedRates[0], sortedRates[ROUNDS - 1], perCall, firstMs, load, frac),
                    firstMs, median);
        }

        private String line(boolean cold, double rate, double ms50, double ms90, double min, double max, double[] phases,
                double firstMs, double load, double frac) {
            if (frac > 1.0) {
                System.err.printf(Locale.ROOT, "verification failed: %s %s %s batch %d: error %.3f of the bound%n", impl, preset,
                        weights, batch, frac);
                System.exit(2);
            }
            String bucket = impl.equals("tornado") ? Integer.toString(TornadoNetwork.bucket(batch, TornadoNetwork.DEFAULT_MAX_BATCH)) : NA;
            String lanes = impl.equals("vector") ? Integer.toString(VectorNetwork.lanes()) : NA;
            String copyIn = phases == null ? NA : f(phases[0]);
            String kernel = phases == null ? NA : f(phases[1]);
            String copyOut = phases == null ? NA : f(phases[2]);
            String priors = phases == null ? NA : f(phases[3]);
            return String.join("\t", "java", impl, device, preset, weights, Integer.toString(batch), bucket, lanes,
                    cold ? NA : String.format(Locale.ROOT, "%.1f", rate), cold ? NA : f(ms50), cold ? NA : f(ms90),
                    cold ? NA : String.format(Locale.ROOT, "%.1f", min), cold ? NA : String.format(Locale.ROOT, "%.1f", max),
                    copyIn, NA, kernel, NA, copyOut, priors, f(firstMs), String.format(Locale.ROOT, "%.2f", load), "ok");
        }

        private double verify(float[] logits, float[] values, int firstRef) {
            return GoldenFiles.compare(logits, values, ref[0], ref[1], firstRef, batch, players, extra)[0];
        }
    }

    /** The line of a case and what the next batch needs from it. */
    private record Result(String line, double firstCallMs, double rateMedian) {
    }

    /** {executions, copy in, execute, copy out, priors} totals of a TornadoNetwork, in ms; zeros for the CPU paths. */
    private static double[] phases(TornadoNetwork tornado) {
        if (tornado == null) {
            return new double[5];
        }
        Matcher m = TIMINGS.matcher(tornado.timings());
        if (!m.find()) {
            throw new IllegalStateException("cannot read " + tornado.timings());
        }
        return new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)),
                Double.parseDouble(m.group(4)), Double.parseDouble(m.group(5))};
    }

    /** The second pass: a profiled instance, median wall, kernel, write and read times of 20 calls per batch. */
    private static void profiled(GoldenFiles golden, List<String> presets, int[] batches, float[] all)
            throws IOException {
        System.out.println("#profiled-columns\tpreset\tweights\tbatch\twall_ms_median\tkernel_ms_median\tdevice_write_ms_median"
                + "\tdevice_read_ms_median\t(TornadoVM profiler, slows the call; NA = the backend reports no value)");
        for (String preset : presets) {
            Parameters parameters = golden.weights(preset);
            int size = parameters.config().inputSize();
            int players = parameters.config().players();
            try (TornadoNetwork gpu = TornadoNetwork.profiled(parameters, TornadoSupport.defaultDevice(),
                    TornadoNetwork.DEFAULT_MAX_BATCH)) {
                for (int batch : batches) {
                    float[] in = Arrays.copyOf(all, batch * size);
                    float[] logits = new float[batch * Encoder.POLICY_SIZE];
                    float[] values = new float[batch * players];
                    for (int i = 0; i < WARMUP_CALLS; i++) {
                        gpu.forward(in, batch, logits, values);
                    }
                    double[] wall = new double[PROFILED_CALLS];
                    double[] kernel = new double[PROFILED_CALLS];
                    double[] write = new double[PROFILED_CALLS];
                    double[] read = new double[PROFILED_CALLS];
                    for (int i = 0; i < PROFILED_CALLS; i++) {
                        long a = System.nanoTime();
                        gpu.forward(in, batch, logits, values);
                        wall[i] = (System.nanoTime() - a) / 1e6;
                        var result = gpu.lastProfilerResult();
                        kernel[i] = result.getDeviceKernelTime() / 1e6;
                        write[i] = result.getDeviceWriteTime() / 1e6;
                        read[i] = result.getDeviceReadTime() / 1e6;
                    }
                    System.out.printf(Locale.ROOT, "#profiled\t%s\t%s\t%d\t%.3f\t%s\t%s\t%s%n",
                            preset.equals("small") ? "small" : "large", preset.equals("gen11") ? "gen11" : "u5", batch,
                            median(wall), orNa(median(kernel)), orNa(median(write)), orNa(median(read)));
                }
            }
        }
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static String orNa(double v) {
        return v > 0 ? String.format(Locale.ROOT, "%.3f", v) : NA;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, b.getCollectionCount());
        }
        return n;
    }

    private static long gcMillis() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, b.getCollectionTime());
        }
        return n;
    }

    private static void printHeader(Options o, String deviceName) {
        var runtime = ManagementFactory.getRuntimeMXBean();
        System.out.println("# CrossBench (Java side of the cross-language network benchmark)");
        System.out.println("# date " + java.time.LocalDate.now());
        System.out.printf("# java %s (%s), %s %s %s, %d available cores%n", System.getProperty("java.version"), runtime.getVmName(),
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        System.out.println("# cpu " + cpuModel());
        System.out.println("# jvm flags " + String.join(" ", runtime.getInputArguments().stream()
                .filter(a -> !a.startsWith("--module-path") && !a.startsWith("--patch-module") && !a.startsWith("--upgrade-module-path")
                        && !a.startsWith("-Djava.library.path") && !a.startsWith("--add-opens") && !a.startsWith("--add-exports"))
                .map(CrossBench::maskPath).toList()));
        System.out.println("# gc " + ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList());
        System.out.println("# vector api " + VectorSupport.describe() + "; lanes used " + VectorNetwork.lanes());
        System.out.println("# UseAVX " + vmOption("UseAVX") + ", MaxVectorSize " + vmOption("MaxVectorSize"));
        System.out.println("# device " + deviceName);
        System.out.println("# tornado: kernel_ms is the TornadoVM execute figure (host to device, kernels, device to host);"
                + " h2d_ms and d2h_ms are NA; phase figures are deltas of timings() over the measured rounds");
        System.out.printf(Locale.ROOT, "# load_1min before %.2f%n", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        System.out.println("# golden " + "cpp/golden manifest checked; presets " + o.presets() + ", batches " + Arrays.toString(o.batches())
                + ", impls " + o.impls() + (o.anchors() ? ", anchors only" : "") + (o.cold() ? ", cold" : ""));
    }

    /** A flag value that is an absolute path becomes {@code <path>}: result files hold no paths. */
    private static String maskPath(String flag) {
        return flag.replaceAll("(?<==)/\\S+|^/\\S+", "<path>");
    }

    private static String vmOption(String name) {
        try {
            return ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption(name).getValue();
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return "n/a";
        }
    }

    private static String cpuModel() {
        try {
            Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor() == 0 && !text.isEmpty()) {
                return text;
            }
        } catch (IOException | InterruptedException e) {
            // fall through
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return System.getProperty("os.arch");
    }

    private static Options parse(String[] args) {
        Path golden = Path.of("cpp/golden");
        List<String> presets = List.of("small", "large");
        int[] batches = {1, 16, 64, 256, 512, 1024};
        List<String> impls = List.of("scalar", "vector", "tornado");
        boolean anchors = false;
        boolean cold = false;
        boolean profiled = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--golden" -> golden = Path.of(args[++i]);
                case "--presets" -> presets = List.of(args[++i].split(","));
                case "--batches" -> batches = Arrays.stream(args[++i].split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
                case "--impls" -> impls = List.of(args[++i].split(","));
                case "--anchors" -> anchors = true;
                case "--cold" -> cold = true;
                case "--no-profiled" -> profiled = false;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        for (int b : batches) {
            if (b < 1 || b > GoldenFiles.POSITIONS) {
                throw new IllegalArgumentException("batch " + b + " is not in 1.." + GoldenFiles.POSITIONS);
            }
        }
        return new Options(golden, presets, batches, impls, anchors, cold, profiled);
    }
}
