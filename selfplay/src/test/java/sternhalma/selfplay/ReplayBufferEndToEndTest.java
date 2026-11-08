package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Encoder;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Orientation;
import sternhalma.nn.Parameters;
import sternhalma.nn.ReferenceTrainer;
import sternhalma.nn.TrainingBatch;
import sternhalma.search.Strategies;

/**
 * Real games of the E05 tool code at a few hundred nodes per move, written as a shard, loaded into the buffer,
 * expanded and trained on.
 */
class ReplayBufferEndToEndTest {

    static final String SPEC = Strategies.BEST_R1_SPEC + ",nodes=100,tt=14";

    @TempDir
    static Path dir;

    static List<GameRecord> games = new ArrayList<>();

    @BeforeAll
    static void playAndWrite() throws IOException {
        SelfPlayGame.Settings settings = SelfPlayGameTest.settings(SPEC, 0, true, Adjudication.Config.OFF);
        SelfPlayGame game = new SelfPlayGame(settings);
        for (int id = 0; id < 4; id++) {
            games.add(game.play(id));
        }
        GameShard.write(dir.resolve("samples-s1006-0000.safetensors"), settings, 0, 0, games);
    }

    /** The positions of the samples in file order: every ply before the labelled position of each game. */
    private static List<Position> positions() {
        List<Position> result = new ArrayList<>();
        for (GameRecord g : games) {
            Position p = Position.initial(RuleSet.defaults(2));
            for (int s = 0; s < g.samples().count(); s++) {
                result.add(p.copy());
                p.make(g.moves()[s]);
            }
        }
        return result;
    }

    @Test
    void everySampleOfTheShardExpandsToTheEncoderPlanesOfItsPosition() throws IOException {
        ReplayBuffer buffer = new ReplayBuffer(2, 1_000_000, true);
        ReplayBuffer.Loaded loaded = buffer.addGeneration(0, dir);
        List<Position> positions = positions();
        assertEquals(1, loaded.files());
        assertEquals(positions.size(), loaded.samples());
        assertEquals(positions.size(), buffer.size());
        assertTrue(buffer.size() > 200, "samples: " + buffer.size());

        int n = buffer.size();
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        TrainingBatch batch = buffer.batchOf(all, new boolean[n]);
        Set<Integer> movers = new HashSet<>();
        float[] expected = new float[Encoder.INPUT_SIZE];
        int openingSamples = 0;
        for (int i = 0; i < n; i++) {
            Position p = positions.get(i);
            movers.add(p.sideToMove());
            Encoder.encode(p, expected, 0);
            assertArrayEquals(expected, Arrays.copyOfRange(batch.input(), i * Encoder.INPUT_SIZE,
                    (i + 1) * Encoder.INPUT_SIZE), "sample " + i);
            // the legal moves in canonical cells, in the order of the file
            Orientation o = Orientation.of(p);
            int[] legal = p.legalMoves();
            assertEquals(legal.length, batch.moveOffsets()[i + 1] - batch.moveOffsets()[i]);
            for (int m = 0; m < legal.length; m++) {
                int cm = o.toCanonicalMove(legal[m]);
                assertEquals(Move.from(cm), batch.moveFrom()[batch.moveOffsets()[i] + m]);
                assertEquals(Move.to(cm), batch.moveTo()[batch.moveOffsets()[i] + m]);
            }
            if (batch.policyWeight()[i] == 0f) {
                openingSamples++;
            }
        }
        assertEquals(2, movers.size());
        int expectedOpening = games.stream().mapToInt(GameRecord::openingPlies).sum();
        assertEquals(expectedOpening, openingSamples, "random opening plies have policy weight 0");
    }

    @Test
    void aBatchTrainsWithAFiniteLossAndAGradient() throws IOException {
        ReplayBuffer buffer = new ReplayBuffer(2, 1_000_000, true);
        buffer.addGeneration(0, dir);
        TrainingBatch batch = buffer.batch(42, 0, 0, 32);
        assertEquals(32, batch.size());
        ReferenceTrainer trainer = new ReferenceTrainer(Parameters.init(NetworkConfig.tiny(2), 1), 1.0);
        ReferenceTrainer.Result result = trainer.accumulate(batch, 8, 2);
        assertTrue(Double.isFinite(result.loss()) && result.loss() > 0, "loss " + result.loss());
        assertTrue(Double.isFinite(result.policyLoss()) && Double.isFinite(result.valueLoss()));
        double norm = 0;
        for (double[] g : trainer.gradients().tensors().values()) {
            for (double v : g) {
                assertTrue(Double.isFinite(v));
                norm += v * v;
            }
        }
        assertTrue(norm > 0, "the gradient is not zero");
        // the same step on the same buffer is the same loss, bit for bit
        ReferenceTrainer again = new ReferenceTrainer(Parameters.init(NetworkConfig.tiny(2), 1), 1.0);
        assertEquals(result.loss(), again.accumulate(buffer.batch(42, 0, 0, 32), 8, 2).loss());
    }
}
