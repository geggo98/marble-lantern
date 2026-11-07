package sternhalma.selfplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes the layer-2 regression set (a test resource) from control games: every game in which a looser margin
 * (400 on one own move) labels the side that went on to lose or not to win, every game with a wrong label of the
 * shipped margin (500 on two own moves), and the first {@code correct} games in which the shipped margin fired
 * correctly. Moves-only text with the stored search scores:
 * {@code <id> <final value 0> <final value 1> <n> <move_1> <score_1> ... <move_n> <score_n>}.
 *
 * <pre>
 *   java -cp ... sternhalma.selfplay.RegressionSetExport <shard dir> <out file> [correct]
 * </pre>
 */
final class RegressionSetExport {

    private RegressionSetExport() {
    }

    public static void main(String[] args) throws Exception {
        int correct = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        List<GameRecord> games = new ArrayList<>();
        for (Path f : SelfPlay.shardFiles(List.of(args[0]))) {
            games.addAll(GameShard.read(f).games());
        }
        StringBuilder sb = new StringBuilder("# E05 layer-2 regression set from control games of " + args[0] + "\n");
        int nearMiss = 0;
        int wrong = 0;
        int right = 0;
        for (GameRecord g : games) {
            if (!g.playedOut()) {
                continue;
            }
            Adjudication.Firing loose = Report.marginFiring(g, 400, 1);
            Adjudication.Firing shipped = Report.marginFiring(g, 500, 2);
            boolean looseWrong = loose != null && g.finalValues()[loose.winner()] != 1.0;
            boolean shippedWrong = shipped != null && g.finalValues()[shipped.winner()] != 1.0;
            boolean take = looseWrong || shippedWrong || shipped != null && right < correct;
            if (!take) {
                continue;
            }
            nearMiss += looseWrong ? 1 : 0;
            wrong += shippedWrong ? 1 : 0;
            right += shipped != null && !shippedWrong ? 1 : 0;
            sb.append(g.id()).append(' ').append(g.finalValues()[0]).append(' ').append(g.finalValues()[1]).append(' ')
                    .append(g.moves().length);
            for (int i = 0; i < g.moves().length; i++) {
                sb.append(' ').append(g.moves()[i]).append(' ').append(g.scores()[i]);
            }
            sb.append('\n');
        }
        Files.writeString(Path.of(args[1]), sb);
        System.err.printf(Locale.ROOT, "%d near misses of margin 400x1, %d wrong and %d right of 500x2%n", nearMiss,
                wrong, right);
    }
}
