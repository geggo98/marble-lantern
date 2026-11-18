package sternhalma.engine;

/** The stream of {@code new SplittableRandom(seed).nextLong()} (SplitMix64); TeaVM's class library has no SplittableRandom. */
final class SplitMix64 {
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;
    private long seed;

    SplitMix64(long seed) {
        this.seed = seed;
    }

    long nextLong() {
        long z = seed += GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
