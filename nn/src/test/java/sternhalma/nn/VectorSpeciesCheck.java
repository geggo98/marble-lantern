package sternhalma.nn;

import java.util.Random;

/**
 * The program that {@link VectorSpeciesTest} starts once per species: {@link VectorNetwork} against the oracle
 * {@link Network} for channel counts and hidden sizes that are no multiple of any lane count (so that every tail
 * path of every species is used), at batch 1 and 5. Prints {@code OK <bits> <lanes> <largest error / bound>}, and
 * exits with 1 and a message on the first output outside {@link Network#agrees}.
 */
public final class VectorSpeciesCheck {

    private VectorSpeciesCheck() {
    }

    public static void main(String[] args) {
        NetworkConfig[] configs = {
            new NetworkConfig(5, 1, 2, 7, PlaneSet.PLAIN),
            new NetworkConfig(13, 2, 3, 9, PlaneSet.PLAIN),
            new NetworkConfig(33, 1, 2, 35, PlaneSet.PLAIN),
            new NetworkConfig(64, 1, 2, 17, PlaneSet.PLAIN),
            NetworkConfig.tiny(2),
        };
        double worst = 0;
        long seed = 1;
        for (NetworkConfig config : configs) {
            for (int batch : new int[] {1, 5}) {
                Parameters parameters = Parameters.uniform(config, seed,
                        (float) Math.sqrt(6.0 / (7 * config.channels())));
                float[] input = new float[batch * Encoder.INPUT_SIZE];
                Random random = new Random(seed++);
                for (int b = 0; b < batch; b++) {
                    for (int i = 0; i < HexGrid.CELLS * Encoder.PLANES; i++) {
                        input[b * Encoder.INPUT_SIZE + i] = random.nextFloat();
                    }
                }
                int players = config.players();
                float[] refPolicy = new float[batch * Encoder.POLICY_SIZE];
                float[] refValues = new float[batch * players];
                new Network(parameters).forward(input, batch, refPolicy, refValues);
                float[] policy = new float[refPolicy.length];
                float[] values = new float[refValues.length];
                new VectorNetwork(parameters).forward(input, batch, policy, values);
                worst = Math.max(worst, compare(config, "policy", policy, refPolicy));
                worst = Math.max(worst, compare(config, "value", values, refValues));
            }
        }
        System.out.printf("OK %d %d %.4f%n", VectorSupport.SPECIES.vectorBitSize(), VectorSupport.lanes(), worst);
    }

    private static double compare(NetworkConfig config, String what, float[] fast, float[] ref) {
        double worst = 0;
        for (int i = 0; i < fast.length; i++) {
            double bound = Network.ABSOLUTE_TOLERANCE + Network.RELATIVE_TOLERANCE * Math.abs(ref[i]);
            double error = Math.abs((double) fast[i] - ref[i]);
            if (!(error <= bound)) {
                System.out.println("FAIL " + config + " " + what + " " + i + ": fast " + fast[i] + ", reference "
                        + ref[i]);
                System.exit(1);
            }
            worst = Math.max(worst, error / bound);
        }
        return worst;
    }
}
