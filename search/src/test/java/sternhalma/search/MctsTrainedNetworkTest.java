package sternhalma.search;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sternhalma.engine.RuleSet;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Networks;
import sternhalma.nn.Parameters;
import sternhalma.search.contract.PositionGenerator;
import sternhalma.search.contract.StrategyChecks;

/**
 * The contract with a network that can play: the generation-0 candidate of {@code data/gen0run2} (git-ignored; found
 * by walking up from the working directory, so a worktree finds the main checkout's data). Skipped, not failed, when
 * the file is absent. It is the check that MCTS never loses a game to a random player, which a random network cannot
 * give.
 */
class MctsTrainedNetworkTest {

    static Path candidate() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path file = dir.resolve("data/gen0run2/gen-0000/candidate.safetensors");
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private static Strategy strategy(Path file, int sims) throws IOException {
        Parameters parameters = Parameters.load(file);
        ForwardPass net = Networks.fastest(parameters); // one thread plays here, so one network may be shared
        MctsOptions options = MctsOptions.defaults().withSims(sims).withBatch(8);
        return new Strategy("mcts(candidate, " + sims + " sims)", Set.of(2), true,
                seed -> MctsPlayer.create(net, options, seed, "mcts"));
    }

    @Test
    void neverLosesToARandomPlayerAndPlaysCleanSelfPlay() throws IOException {
        Path file = candidate();
        assumeTrue(file != null, "no data/gen0run2/gen-0000/candidate.safetensors");
        assumeTrue(Networks.vectorAvailable(), "the scalar network is too slow for this test");
        Strategy strategy = strategy(file, 16);
        Random random = new Random(20261109);
        for (int i = 0; i < 16; i++) {
            StrategyChecks.assertBeatsRandom(strategy, PositionGenerator.played(RuleSet.defaults(2), random.nextLong(),
                    random.nextInt(5), 0.0), i % 2 == 0, random.nextLong());
        }
        for (int i = 0; i < 6; i++) {
            StrategyChecks.assertCleanSelfPlay(strategy, PositionGenerator.played(RuleSet.defaults(2), random.nextLong(),
                    random.nextInt(150), 0.5), random.nextLong());
        }
    }
}
