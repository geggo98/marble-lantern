package sternhalma.search;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * The random source of {@link Mcts}: the uniform doubles of the Dirichlet noise and of the sampled move, and a
 * standard normal for the gamma sampler. TeaVM's class library has no {@code RandomGenerator} and no
 * {@code SplittableRandom}, so the browser path of {@link Mcts} does not name them: the JVM constructor uses
 * {@link #splittable}, the browser engine uses {@link #portable}.
 */
public interface SearchRandom {

    /** A uniform double in [0, 1). */
    double nextDouble();

    /** A standard normal. */
    double nextGaussian();

    /**
     * The generator of the JVM path: {@code SplittableRandom(seed)}. Every existing seed keeps its stream, so the
     * fingerprints and the arena results of earlier rounds stay valid. JVM only.
     */
    static SearchRandom splittable(long seed) {
        return of(RandomGeneratorFactory.of("SplittableRandom").create(seed));
    }

    /** Any {@link RandomGenerator} as a {@link SearchRandom}. JVM only. */
    static SearchRandom of(RandomGenerator generator) {
        return new SearchRandom() {
            @Override
            public double nextDouble() {
                return generator.nextDouble();
            }

            @Override
            public double nextGaussian() {
                return generator.nextGaussian();
            }
        };
    }

    /**
     * A generator that runs on the JVM and under TeaVM with the same stream: SplitMix64 (the stream of
     * {@code SplittableRandom.nextLong}), doubles from the top 53 bits, normals by the Marsaglia polar method. Its
     * normals are not those of {@link #splittable} (the JDK uses a ziggurat), so a noisy search of the browser
     * engine is reproducible across JVM and browser but does not draw the stream of the JVM players.
     */
    static SearchRandom portable(long seed) {
        return new Portable(seed);
    }

    /** The implementation of {@link #portable}. */
    final class Portable implements SearchRandom {
        private long state;
        private boolean haveSpare;
        private double spare;

        private Portable(long seed) {
            this.state = seed;
        }

        private long nextLong() {
            state += 0x9e3779b97f4a7c15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }

        @Override
        public double nextDouble() {
            return (nextLong() >>> 11) * 0x1.0p-53;
        }

        @Override
        public double nextGaussian() {
            if (haveSpare) {
                haveSpare = false;
                return spare;
            }
            double u;
            double v;
            double s;
            do {
                u = 2 * nextDouble() - 1;
                v = 2 * nextDouble() - 1;
                s = u * u + v * v;
            } while (s >= 1 || s == 0);
            double m = Math.sqrt(-2 * Math.log(s) / s);
            spare = v * m;
            haveSpare = true;
            return u * m;
        }
    }
}
