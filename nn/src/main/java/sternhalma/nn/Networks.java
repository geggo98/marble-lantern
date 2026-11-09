package sternhalma.nn;

/**
 * Which forward pass to use on this JVM. {@link VectorNetwork} needs the incubating module {@code jdk.incubator.vector}
 * (start the JVM with {@code --add-modules jdk.incubator.vector}); without it only the scalar {@link Network} can run,
 * about 15 times slower (docs/network.md, "Measured CPU speed"). A caller that only wants the fastest available path,
 * such as the MCTS players of the module {@code selfplay}, asks here and need not be compiled against the incubator
 * module.
 */
public final class Networks {

    private Networks() {
    }

    /** Whether {@link VectorNetwork} can be used in this JVM. */
    public static boolean vectorAvailable() {
        return ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
    }

    /**
     * The fastest forward pass of the parameters: a new {@link VectorNetwork} (not thread-safe, one per thread) if the
     * Vector API module is present, else the thread-safe scalar {@link Network}.
     */
    public static ForwardPass fastest(Parameters parameters) {
        return vectorAvailable() ? new VectorNetwork(parameters) : new Network(parameters);
    }
}
