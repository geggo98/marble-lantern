package sternhalma.nn;

import java.util.List;
import java.util.Random;
import sternhalma.engine.Move;
import sternhalma.engine.Position;

/** Training batches for tests, built from encoded positions with their own canonical map. */
final class TestBatches {

    private TestBatches() {
    }

    /**
     * One sample per position that has legal moves: the planes from {@link Encoder}, the legal moves in
     * canonical cells with random positive targets (every fourth target is 0), and a seeded win for one
     * absolute player, reordered to relative players. The policy weights cycle through 1, 0.5, 2 and 1.5, the
     * value weights through 1, 2, 0.5 and 1, so that no weight is accidentally the same everywhere.
     */
    static TrainingBatch fromPositions(int players, List<Position> positions, long seed) {
        Random random = new Random(seed);
        TrainingBatch.Builder builder = new TrainingBatch.Builder(players);
        float[] policyWeights = {1f, 0.5f, 2f, 1.5f};
        float[] valueWeights = {1f, 2f, 0.5f, 1f};
        int n = 0;
        for (Position position : positions) {
            int[] moves = position.legalMoves();
            if (moves.length == 0) {
                continue;
            }
            Orientation orientation = Orientation.of(position);
            float[] input = new float[Encoder.INPUT_SIZE];
            Encoder.encode(position, input, 0);
            int[] from = new int[moves.length];
            int[] to = new int[moves.length];
            double[] target = new double[moves.length];
            for (int m = 0; m < moves.length; m++) {
                from[m] = orientation.toCanonical(Move.from(moves[m]));
                to[m] = orientation.toCanonical(Move.to(moves[m]));
                target[m] = m % 4 == 3 ? 0 : 0.1 + random.nextDouble();
            }
            target[0] = 1; // at least one positive target
            int winner = random.nextInt(players);
            double[] absolute = new double[players];
            java.util.Arrays.fill(absolute, -1.0 / (players - 1));
            absolute[winner] = 1;
            float[] z = TrainingBatch.relativeValues(absolute, position.sideToMove());
            builder.add(input, from, to, target, z, policyWeights[n % 4], valueWeights[n % 4]);
            n++;
        }
        return builder.build();
    }

    /**
     * The batch plus one synthetic sample whose moves start and end on the extreme cells 0 and 120, which real
     * positions seldom touch. Without it a bug at the last row of the policy head has no gradient to show.
     */
    static TrainingBatch plusCornerSample(TrainingBatch batch) {
        TrainingBatch.Builder builder = new TrainingBatch.Builder(batch.players());
        for (int s = 0; s < batch.size(); s++) {
            TrainingBatch one = single(batch, s);
            double[] target = new double[one.moveCount()];
            for (int m = 0; m < target.length; m++) {
                target[m] = one.moveTarget()[m];
            }
            builder.add(one.input(), one.moveFrom(), one.moveTo(), target, one.valueTarget(), one.policyWeight()[0],
                    one.valueWeight()[0]);
        }
        TrainingBatch first = single(batch, 0);
        builder.add(first.input(), new int[] {0, 120, 0, 120, 60}, new int[] {120, 0, 0, 120, 61},
                new double[] {0.3, 0.2, 0.1, 0.25, 0.15}, first.valueTarget(), 1f, 1f);
        return builder.build();
    }

    /** A batch of one sample, the sample {@code s} of {@code batch}. */
    static TrainingBatch single(TrainingBatch batch, int s) {
        int first = batch.moveOffsets()[s];
        int end = batch.moveOffsets()[s + 1];
        int p = batch.players();
        float[] input = java.util.Arrays.copyOfRange(batch.input(), s * Encoder.INPUT_SIZE,
                (s + 1) * Encoder.INPUT_SIZE);
        return new TrainingBatch(1, p, input, new int[] {0, end - first},
                java.util.Arrays.copyOfRange(batch.moveFrom(), first, end),
                java.util.Arrays.copyOfRange(batch.moveTo(), first, end),
                java.util.Arrays.copyOfRange(batch.moveTarget(), first, end),
                java.util.Arrays.copyOfRange(batch.valueTarget(), s * p, (s + 1) * p),
                new float[] {batch.policyWeight()[s]}, new float[] {batch.valueWeight()[s]},
                new double[] {batch.targetEntropy()[s]});
    }

    /**
     * {@code n} positions with legal moves that cycle through the movers (mover 0, 1, 2, 0, ...), each taken
     * evenly from the positions of that mover.
     */
    static List<Position> pick(List<Position> positions, int n) {
        List<List<Position>> byMover = new java.util.ArrayList<>();
        for (Position p : positions) {
            if (p.legalMoves().length > 0) {
                while (byMover.size() <= p.sideToMove()) {
                    byMover.add(new java.util.ArrayList<>());
                }
                byMover.get(p.sideToMove()).add(p);
            }
        }
        int movers = byMover.size();
        List<Position> result = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Position> group = byMover.get(i % movers);
            int perMover = (n + movers - 1) / movers;
            result.add(group.get((i / movers) * group.size() / perMover));
        }
        return result;
    }
}
