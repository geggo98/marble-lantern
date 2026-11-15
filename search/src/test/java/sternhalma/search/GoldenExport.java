package sternhalma.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.Rows;

/**
 * Writes the golden files of the C++ port, classical part (docs/cpp-comparison-plan.md, WP1). The C++ tests and
 * benchmarks read them and compare line by line. The files are text (tab-separated, one item per line), generated,
 * never edited and never committed ({@code cpp/golden/} is git-ignored). Everything is deterministic: the same code
 * writes the same bytes.
 *
 * <p>Every file starts with {@code #} comment lines that name the columns; the data lines follow. A move is the int
 * of {@link Move#of}; a move list is its moves separated by blanks, {@code -} for the empty list; a hash is 16 hex
 * digits. {@code MANIFEST.tsv} lists every file with its line count and SHA-256.
 *
 * <pre>
 *   ./gradlew --no-build-cache :search:goldenExport --args='cpp/golden'
 * </pre>
 */
public final class GoldenExport {

    /** Seed of the position keys of {@code Position}. */
    static final long ZOBRIST_SEED = 0x5E7A_11A1_0000_0001L;

    /** Seed of the playouts of {@code positions.tsv}. */
    static final long PLAYOUT_SEED = 20_261_115L;
    static final int POSITIONS = 2000;
    static final int SAMPLE_STRIDE = 4;
    static final int MAX_PLY = 200;
    /** Probability that a playout move is random instead of the greedy one, per playout (index modulo length). */
    static final double[] EPSILON = {1.0, 0.8, 0.6, 0.4, 0.3, 0.2, 0.1, 0.0};

    private static final String R2_NAME = "fingerprint-r2.txt";
    private static final String R3_NAME = "fingerprint-r3.txt";
    private static final String BEST_NAME = "fingerprint-best.txt";

    /** The constants of BestSpecFingerprintTest: positions checksum, game at 1000 nodes, game at 5000 nodes. */
    private static final long[] R2_SUMS = {-8723226000912328943L, -6000360130468970639L, -1429733491373947983L};
    private static final long[] R3_SUMS = {4852147404152522458L, 2660956470353287877L, -3579989785793258016L};

    private final Path out;
    private final List<String[]> manifest = new ArrayList<>();
    private final String commit;

    private final long[] sideKey = new long[3];
    private final long[][] pieceKey = new long[3][Geometry.CELLS];

    /** Positions that have passed the race adjudication test, by move list: source and moves. */
    private final Map<String, Passed> passed = new LinkedHashMap<>();

    private record Passed(String source, int[] moves) {
    }

    private GoldenExport(Path out, String commit) {
        this.out = out;
        this.commit = commit;
        SplittableRandom random = new SplittableRandom(ZOBRIST_SEED);
        for (int p = 0; p < 3; p++) {
            sideKey[p] = random.nextLong();
            for (int c = 0; c < Geometry.CELLS; c++) {
                pieceKey[p][c] = random.nextLong();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "cpp/golden");
        Files.createDirectories(out);
        GoldenExport export = new GoldenExport(out, gitCommit());
        long t0 = System.nanoTime();
        export.zobrist();
        export.geometry();
        export.reference();
        export.positions();
        export.fingerprints();
        export.raceFiles();
        export.writeManifest();
        System.err.printf("golden export: %d files in %s, %.1f s%n", export.manifest.size() + 1, out,
                (System.nanoTime() - t0) / 1e9);
    }

    // ---- zobrist.tsv ----

    private void zobrist() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Position keys: SplittableRandom(seed 0x5E7A11A100000001), draw order: for player 0..2 the side key, then the piece keys of cells 0..120");
        lines.add("# index<TAB>signed decimal key (366 lines: index 0 is the side key of player 0, 1..121 its piece keys, 122 the side key of player 1, and so on) | initial-hash<TAB>hash of Position.initial(RuleSet.defaults(2))");
        int index = 0;
        for (int p = 0; p < 3; p++) {
            lines.add((index++) + "\t" + sideKey[p]);
            for (int c = 0; c < Geometry.CELLS; c++) {
                lines.add((index++) + "\t" + pieceKey[p][c]);
            }
        }
        Position initial = Position.initial(ReferencePositions.rules());
        long hash = hashOf(initial);
        if (hash != initial.hash()) {
            throw new IllegalStateException("the keys do not reproduce Position.hash(): " + hex(hash) + " vs "
                    + hex(initial.hash()));
        }
        lines.add("initial-hash\t" + hex(initial.hash()));
        write("zobrist.tsv", lines);
    }

    /** The hash of a position from the keys: side key of the mover xor the piece keys of all pieces. */
    private long hashOf(Position pos) {
        long h = sideKey[pos.sideToMove()];
        for (int c = 0; c < Geometry.CELLS; c++) {
            int o = pos.owner(c);
            if (o >= 0) {
                h ^= pieceKey[o][c];
            }
        }
        return h;
    }

    // ---- geometry.tsv ----

    private void geometry() throws IOException {
        Layout layout = Layout.of(2);
        List<String> lines = new ArrayList<>();
        lines.add("# cell<TAB>id<TAB>q<TAB>r<TAB>s<TAB>neighbour 0..5<TAB>jump landing 0..5<TAB>corner<TAB>mirror<TAB>playerFlip<TAB>rotate180 (-1: off the board, or the central hexagon)");
        lines.add("# layout<TAB>player<TAB>startCorner<TAB>goalCorner<TAB>maxDistance (two players)");
        lines.add("# dist<TAB>player<TAB>cell<TAB>distance<TAB>allowed (0 or 1) (two players)");
        lines.add("# corner<TAB>index<TAB>the cells of the corner");
        for (int c = 0; c < Geometry.CELLS; c++) {
            StringBuilder b = new StringBuilder("cell\t").append(c).append('\t').append(Geometry.q(c)).append('\t')
                    .append(Geometry.r(c)).append('\t').append(Geometry.s(c));
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                b.append('\t').append(Geometry.neighbor(c, d));
            }
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                b.append('\t').append(Geometry.jumpLanding(c, d));
            }
            b.append('\t').append(Geometry.cornerOf(c)).append('\t').append(Geometry.mirror(c)).append('\t')
                    .append(Geometry.playerFlip(c)).append('\t').append(Geometry.rotate180(c));
            lines.add(b.toString());
        }
        for (int p = 0; p < 2; p++) {
            lines.add("layout\t" + p + "\t" + layout.startCorner(p) + "\t" + layout.goalCorner(p) + "\t"
                    + layout.maxDistance(p));
        }
        for (int p = 0; p < 2; p++) {
            for (int c = 0; c < Geometry.CELLS; c++) {
                lines.add("dist\t" + p + "\t" + c + "\t" + layout.distance(p, c) + "\t" + (layout.allowed(p, c) ? 1 : 0));
            }
        }
        for (int k = 0; k < 6; k++) {
            StringBuilder b = new StringBuilder("corner\t").append(k);
            for (int c : Geometry.cornerCells(k)) {
                b.append('\t').append(c);
            }
            lines.add(b.toString());
        }
        write("geometry.tsv", lines);
    }

    // ---- reference.tsv ----

    private void reference() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# kind (position | opening)<TAB>index<TAB>ply<TAB>hash after the moves<TAB>move list from the initial position");
        lines.add("# position: ReferencePositions.moveLists() (64); opening: ReferencePositions.gameOpening(i) (the 2 full games)");
        List<int[]> lists = ReferencePositions.moveLists();
        for (int i = 0; i < lists.size(); i++) {
            Position pos = ReferencePositions.replay(lists.get(i));
            lines.add("position\t" + i + "\t" + pos.ply() + "\t" + hex(pos.hash()) + "\t" + moves(lists.get(i), lists.get(i).length));
        }
        for (int g = 0; g < ReferencePositions.FULL_GAMES; g++) {
            int[] opening = ReferencePositions.gameOpening(g);
            Position pos = ReferencePositions.replay(opening);
            lines.add("opening\t" + g + "\t" + pos.ply() + "\t" + hex(pos.hash()) + "\t" + moves(opening, opening.length));
        }
        write("reference.tsv", lines);
    }

    // ---- positions.tsv ----

    private void positions() throws IOException {
        Evaluator r1 = Evaluators.byName("linear-r1");
        Evaluator r3 = Evaluators.byName("linear-r3");
        Evaluator race = Evaluators.byName("racelin1-r3");
        List<String> lines = new ArrayList<>();
        lines.add("# " + POSITIONS + " positions of seeded playouts from the initial position (two players, default rules): playout i has seed "
                + PLAYOUT_SEED + " * 1000003 + i and plays a random move with probability EPSILON[i mod 8] = " + java.util.Arrays.toString(EPSILON)
                + ", else a move that minimises the distance sum of the mover (ties: random); positions at plies 0, " + SAMPLE_STRIDE
                + ", ... up to " + MAX_PLY + " and the final position of a playout");
        lines.add("# index<TAB>ply<TAB>side to move<TAB>hash<TAB>move list<TAB>legal moves in generator order<TAB>outcome (none | REASON:winner)<TAB>"
                + "linear-r1<TAB>linear-r3<TAB>racelin1-r3<TAB>futilitySafe(racelin1-r3, depth 1)<TAB>futilitySafe(racelin1-r3, depth 2) (- for a final position)");
        int index = 0;
        for (int game = 0; index < POSITIONS; game++) {
            SplittableRandom random = new SplittableRandom(PLAYOUT_SEED * 1_000_003L + game);
            double epsilon = EPSILON[game % EPSILON.length];
            Position pos = Position.initial(ReferencePositions.rules());
            int[] list = new int[MAX_PLY + 1];
            int n = 0;
            boolean last = false;
            while (index < POSITIONS && !last) {
                boolean final_ = pos.outcome() != null || pos.legalMoves().length == 0 || n >= MAX_PLY;
                if (n % SAMPLE_STRIDE == 0 || final_) {
                    lines.add(positionLine(index, pos, list, n, r1, r3, race));
                    if (pos.outcome() == null && RaceAdjudication.passed(pos)) {
                        notePassed("positions", list, n);
                    }
                    index++;
                }
                if (final_) {
                    last = true;
                } else {
                    int m = chooseMove(pos, random, epsilon);
                    list[n++] = m;
                    pos.make(m);
                }
            }
        }
        write("positions.tsv", lines);
    }

    private String positionLine(int index, Position pos, int[] list, int n, Evaluator r1, Evaluator r3, Evaluator race) {
        long recomputed = hashOf(pos);
        if (recomputed != pos.hash()) {
            throw new IllegalStateException("hash mismatch at position " + index);
        }
        Outcome outcome = pos.outcome();
        StringBuilder b = new StringBuilder();
        b.append(index).append('\t').append(pos.ply()).append('\t').append(pos.sideToMove()).append('\t')
                .append(hex(pos.hash())).append('\t').append(moves(list, n)).append('\t');
        int[] legal = pos.legalMoves();
        b.append(moves(legal, legal.length)).append('\t');
        b.append(outcome == null ? "none" : outcome.reason() + ":" + outcome.winner());
        if (outcome == null) {
            b.append('\t').append(r1.evaluate(pos)).append('\t').append(r3.evaluate(pos)).append('\t')
                    .append(race.evaluate(pos)).append('\t').append(race.futilitySafe(pos, 1) ? 1 : 0).append('\t')
                    .append(race.futilitySafe(pos, 2) ? 1 : 0);
        } else {
            b.append("\t-\t-\t-\t-\t-");
        }
        return b.toString();
    }

    private static int chooseMove(Position pos, SplittableRandom random, double epsilon) {
        int[] legal = pos.legalMoves();
        if (random.nextDouble() < epsilon) {
            return legal[random.nextInt(legal.length)];
        }
        int mover = pos.sideToMove();
        int best = Integer.MAX_VALUE;
        int[] ties = new int[legal.length];
        int count = 0;
        for (int m : legal) {
            pos.make(m);
            int d = pos.distanceSum(mover);
            pos.unmake();
            if (d < best) {
                best = d;
                count = 0;
            }
            if (d == best) {
                ties[count++] = m;
            }
        }
        return ties[random.nextInt(count)];
    }

    private void notePassed(String source, int[] list, int n) {
        String key = moves(list, n);
        passed.putIfAbsent(key, new Passed(source, java.util.Arrays.copyOf(list, n)));
    }

    // ---- fingerprints ----

    private void fingerprints() throws IOException {
        fingerprint("r2", R2_NAME, ReferencePositions.R2_SPEC, R2_SUMS);
        fingerprint("r3", R3_NAME, ReferencePositions.R3_SPEC, R3_SUMS);
        fingerprint("best", BEST_NAME, Strategies.BEST_SPEC + ",nodes=20000", R3_SUMS);
    }

    /** The text that {@code Fingerprint.main} prints for one spec, checked against the recorded constants. */
    private void fingerprint(String label, String file, String spec, long[] sums) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("spec " + spec);
        Fingerprint.Part positions = Fingerprint.positions(spec);
        positions.lines().forEach(l -> lines.add("  " + l));
        lines.add("  positions checksum " + positions.checksum() + "L");
        expect(label + " positions", sums[0], positions.checksum());
        for (int i = 0; i < Fingerprint.GAME_NODES.length; i++) {
            long nodes = Fingerprint.GAME_NODES[i];
            Fingerprint.Part game = Fingerprint.game(spec, nodes);
            game.lines().forEach(l -> lines.add("  game@" + nodes + " " + l));
            lines.add("  game@" + nodes + " checksum " + game.checksum() + "L");
            expect(label + " game@" + nodes, sums[1 + i], game.checksum());
            traceGame(label + "-game" + nodes, spec, nodes, game.checksum());
        }
        write(file, lines);
    }

    private static void expect(String what, long recorded, long actual) {
        if (recorded != actual) {
            throw new IllegalStateException("fingerprint " + what + " differs from BestSpecFingerprintTest: " + actual
                    + " instead of " + recorded);
        }
    }

    /**
     * Plays the fingerprint game again, as {@link Fingerprint#game} does, and notes every position in which the race
     * adjudication test passes. The checksum must equal the one of the fingerprint.
     */
    private void traceGame(String source, String spec, long nodes, long expected) {
        PlayerSpec p = PlayerSpec.parse(Fingerprint.withNodes(spec, nodes));
        int[] opening = ReferencePositions.gameOpening(0);
        Position pos = ReferencePositions.replay(opening);
        AlphaBetaPlayer[] players = {p.create(), p.create()};
        int[] list = java.util.Arrays.copyOf(opening, Position.MAX_MOVES);
        int n = opening.length;
        long h = 17;
        while (pos.outcome() == null && pos.legalMoves().length > 0) {
            if (RaceAdjudication.passed(pos)) {
                notePassed(source, list, n);
            }
            int m = players[pos.sideToMove()].choose(pos);
            h = h * 31 + m;
            list[n++] = m;
            pos.make(m);
        }
        h = h * 31 + players[0].totalNodes() * 7 + players[1].totalNodes();
        expect("replayed " + source, expected, h);
    }

    // ---- race-counts.tsv, race-moves.tsv ----

    private void raceFiles() throws IOException {
        RaceTable table = RaceTable.rows1();
        List<String> counts = new ArrayList<>();
        counts.add("# Positions in which RaceAdjudication.passed holds: those of positions.tsv and of the fingerprint games, each move list once");
        counts.add("# source<TAB>ply<TAB>move list<TAB>rows>=1 table count of player 0<TAB>count of player 1 (cells mapped with Rows.toPlayer0; -1: not covered)");
        List<String> raceMoves = new ArrayList<>();
        raceMoves.add("# The same positions: new RaceMove(RaceTable.rows1(), 1).choose(pos), a fresh RaceMove for every position");
        raceMoves.add("# source<TAB>ply<TAB>move list<TAB>move (-1: none, the search decides)<TAB>origin (table | plan | none)");
        for (Map.Entry<String, Passed> e : passed.entrySet()) {
            Passed ps = e.getValue();
            Position pos = ReferencePositions.replay(ps.moves());
            int c0 = table.count(Rows.toPlayer0(0, pos.pieces(0)));
            int c1 = table.count(Rows.toPlayer0(1, pos.pieces(1)));
            counts.add(ps.source() + "\t" + pos.ply() + "\t" + e.getKey() + "\t" + c0 + "\t" + c1);
            RaceMove race = new RaceMove(table, 1);
            int move = race.choose(pos);
            String origin = move < 0 ? "none" : race.tableMoves() > 0 ? "table" : "plan";
            raceMoves.add(ps.source() + "\t" + pos.ply() + "\t" + e.getKey() + "\t" + move + "\t" + origin);
        }
        write("race-counts.tsv", counts);
        write("race-moves.tsv", raceMoves);
    }

    // ---- output ----

    private static String moves(int[] list, int n) {
        if (n == 0) {
            return "-";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b.append(' ');
            }
            b.append(list[i]);
        }
        return b.toString();
    }

    private static String hex(long value) {
        return String.format("%016x", value);
    }

    /** Writes a file under a temporary name and renames it, so that a file with its final name is complete. */
    private void write(String name, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        Path tmp = out.resolve(name + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, out.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        manifest.add(new String[] {name, Integer.toString(lines.size()), sha256(bytes)});
    }

    private void writeManifest() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("file\tlines\tsha256\tcommit");
        for (String[] entry : manifest) {
            lines.add(entry[0] + "\t" + entry[1] + "\t" + entry[2] + "\t" + commit);
        }
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        Path tmp = out.resolve("MANIFEST.tsv.tmp");
        Files.writeString(tmp, text.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, out.resolve("MANIFEST.tsv"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The commit of the working tree (for the manifest), or {@code unknown} outside a git checkout. */
    private static String gitCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && text.matches("[0-9a-f]{40}") ? text : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
