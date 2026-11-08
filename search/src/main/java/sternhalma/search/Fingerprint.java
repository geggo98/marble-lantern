package sternhalma.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sternhalma.engine.Move;
import sternhalma.engine.Position;

/**
 * The search-tree fingerprint of a player spec: what it plays and how many nodes it needs on the
 * {@link ReferencePositions}. Two builds or two specs that claim to search the identical tree must produce the
 * same fingerprint; any difference in move ordering, pruning, hashing, tie-breaking or evaluation shows up as
 * a different node count or move somewhere.
 *
 * <p>Three parts, each a checksum:
 * <ul>
 *   <li>{@link #positions(String)}: per reference position, move, score, completed depth and node count of the
 *       spec's node-limited search, and move, score and exact node count of a search to the fixed depth
 *       {@value #FIXED_DEPTH} without a node limit (a fresh searcher each time, so the positions are independent),
 *       plus the move lists of the positions themselves. The fixed-depth count matters: a node-limited search
 *       nearly always reports exactly its limit, so only the fixed-depth count measures the size of the tree;</li>
 *   <li>{@link #game(String, long)} at 1000 and at 5000 nodes: the move sequence and the node totals of a full
 *       self-play game from {@link ReferencePositions#gameOpening(int) full-game opening} 0, with the players
 *       keeping their tables between moves as in the arena. A move of the race module (E03) enters the move
 *       sequence like any other and costs no nodes, so race play changes the checksum through both.</li>
 * </ul>
 * {@code BestSpecFingerprintTest} records these for R2 ({@link ReferencePositions#R2_SPEC}), for R3
 * ({@link ReferencePositions#R3_SPEC}) and for {@link Strategies#BEST_SPEC}. Run this class to print them with one
 * line per position, which shows where two trees diverge; the lines also name the {@link Scores.Kind band} of every
 * score and mark the moves the race module chose, which the checksum covers only through moves and node counts:
 * <pre>
 *   devenv shell -- ./gradlew --no-build-cache :search:fingerprint --args='&lt;spec&gt;'
 * </pre>
 */
public final class Fingerprint {

    /** Depth of the second, node-unlimited search per position. */
    public static final int FIXED_DEPTH = 4;

    /** Node budgets of the two fingerprint games. */
    public static final long[] GAME_NODES = {1_000, 5_000};

    /** One search of a reference position. */
    public record Entry(int index, int ply, int move, int score, int depth, long nodes,
            int fixedMove, int fixedScore, long fixedNodes) {

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "%2d ply=%3d move=%-15s score=%8d depth=%2d nodes=%6d | depth %d: move=%-15s score=%8d nodes=%d",
                    index, ply, Move.toString(move), score, depth, nodes, FIXED_DEPTH, Move.toString(fixedMove),
                    fixedScore, fixedNodes);
        }
    }

    /** A fingerprint part: the checksum and the lines it was computed from. */
    public record Part(long checksum, List<String> lines) {
    }

    private Fingerprint() {
    }

    /** Searches every reference position once with {@code spec} (a fresh searcher per position). */
    public static List<Entry> searches(String spec) {
        PlayerSpec p = parseNodeLimited(spec);
        List<int[]> lists = ReferencePositions.moveLists();
        List<Entry> entries = new ArrayList<>(lists.size());
        for (int i = 0; i < lists.size(); i++) {
            Position pos = ReferencePositions.replay(lists.get(i));
            SearchResult r = searcher(p).search(pos, p.depth(), p.millis(), p.nodes());
            SearchResult f = searcher(p).search(pos, Math.min(FIXED_DEPTH, p.depth()), Long.MAX_VALUE, Long.MAX_VALUE);
            entries.add(new Entry(i, pos.ply(), r.move(), r.score(), r.depth(), r.nodes(), f.move(), f.score(),
                    f.nodes()));
        }
        return entries;
    }

    private static AlphaBeta searcher(PlayerSpec p) {
        return new AlphaBeta(Evaluators.byName(p.evaluator(), p.weights()), p.tableBits(), true, p.options());
    }

    /** Checksum over the reference move lists and the {@link #searches(String)} of {@code spec}. */
    public static Part positions(String spec) {
        long h = 17;
        for (int[] list : ReferencePositions.moveLists()) {
            for (int m : list) {
                h = h * 31 + m;
            }
            h = h * 31 + list.length;
        }
        List<String> lines = new ArrayList<>();
        for (Entry e : searches(spec)) {
            h = h * 31 + e.move();
            h = h * 31 + e.score();
            h = h * 31 + e.depth();
            h = h * 31 + e.nodes();
            h = h * 31 + e.fixedMove();
            h = h * 31 + e.fixedScore();
            h = h * 31 + e.fixedNodes();
            lines.add(e.toString() + " | kinds " + Scores.kind(e.score()) + "/" + Scores.kind(e.fixedScore()));
        }
        return new Part(h, List.copyOf(lines));
    }

    /**
     * Checksum over the moves of a self-play game of {@code spec} at {@code nodes} nodes per move from full-game
     * opening 0, and over both players' node totals.
     */
    public static Part game(String spec, long nodes) {
        PlayerSpec p = parseNodeLimited(withNodes(spec, nodes));
        Position pos = ReferencePositions.replay(ReferencePositions.gameOpening(0));
        AlphaBetaPlayer[] players = {p.create(), p.create()};
        long h = 17;
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        StringBuilder kinds = new StringBuilder();
        while (pos.outcome() == null && pos.legalMoves().length > 0) {
            AlphaBetaPlayer player = players[pos.sideToMove()];
            int m = player.choose(pos);
            h = h * 31 + m;
            Scores.Kind kind = player.lastScoreKind();
            kinds.append(kind == null ? 'r' : kind.name().charAt(0));
            line.append(Move.toString(m)).append(' ');
            pos.make(m);
        }
        h = h * 31 + players[0].totalNodes() * 7 + players[1].totalNodes();
        lines.add("moves " + line.toString().trim());
        lines.add("plies=" + pos.ply() + " nodes=" + players[0].totalNodes() + "/" + players[1].totalNodes());
        lines.add("kinds " + kinds + " (O ordinary, R race band, M mate, r race move) race-moves="
                + players[0].raceMoves() + "/" + players[1].raceMoves());
        return new Part(h, List.copyOf(lines));
    }

    /** {@code spec} with its node budget replaced by {@code nodes}. */
    public static String withNodes(String spec, long nodes) {
        String[] head = spec.split(":", 2);
        StringBuilder b = new StringBuilder(head[0]).append(':');
        if (head.length == 2) {
            for (String pair : head[1].split(",")) {
                if (!pair.isBlank() && !pair.trim().startsWith("nodes=")) {
                    b.append(pair.trim()).append(',');
                }
            }
        }
        return b.append("nodes=").append(nodes).toString();
    }

    private static PlayerSpec parseNodeLimited(String spec) {
        PlayerSpec p = PlayerSpec.parse(spec);
        if (p.millis() != Long.MAX_VALUE) {
            throw new IllegalArgumentException("a fingerprint needs a node limit, not a time limit: " + spec);
        }
        return p;
    }

    /** Prints the three parts of the fingerprint of each spec given (default: R2, R3 and BEST_SPEC). */
    public static void main(String[] args) {
        List<String> specs = args.length == 0
                ? List.of(ReferencePositions.R2_SPEC, ReferencePositions.R3_SPEC, Strategies.BEST_SPEC + ",nodes=20000")
                : List.of(args);
        for (String spec : specs) {
            System.out.println("spec " + spec);
            Part positions = positions(spec);
            positions.lines().forEach(l -> System.out.println("  " + l));
            System.out.println("  positions checksum " + positions.checksum() + "L");
            for (long nodes : GAME_NODES) {
                Part game = game(spec, nodes);
                game.lines().forEach(l -> System.out.println("  game@" + nodes + " " + l));
                System.out.println("  game@" + nodes + " checksum " + game.checksum() + "L");
            }
        }
    }
}
