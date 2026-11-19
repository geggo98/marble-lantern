package sternhalma.webnet;

import java.io.UncheckedIOException;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Encoder;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.PlaneSet;
import sternhalma.search.Mcts;
import sternhalma.search.MctsOptions;
import sternhalma.search.MctsResult;
import sternhalma.search.RaceMove;
import sternhalma.search.SearchRandom;
import sternhalma.tablebase.RaceTable;

/**
 * The net-MCTS engine of the browser without its forward pass: the search ({@link Mcts}, stepwise), the encoding of
 * the positions, the softmax over the legal moves and the mapping of the values run here; the forward pass is done by
 * the caller (C++ WebAssembly or WebGPU, web/src/engines/net/backend.ts). Plain Java, so the JVM tests and the TeaVM
 * build run the same code; {@link WebNet} is the string and array surface that TeaVM exports.
 *
 * <p>The move history is the game state. {@link #begin} replays it from the start position, then the search asks for
 * network outputs in batches: {@link #prepare}, {@link #inputs}, {@link #apply}, until {@code prepare} returns 0,
 * then {@link #best}. No file system, no threads, no clock apart from the optional time budget.
 *
 * <p>Defaults of the options (the strength check of task S decides whether S1 and race play go on): S1 off, race off,
 * win-in-1 on, no tree reuse, no noise, most visited move, c = 1.
 */
public final class WebNetCore {

    /** The result of {@link #best}. {@code moves} and {@code visits} are empty for a move without a search. */
    public record Best(int move, double rootValue, int sims, int nodes, int exactLeaves, boolean raceMove, int[] moves,
            int[] visits, double[] values) {
    }

    private RuleSet.JumpRule jump = RuleSet.JumpRule.ADJACENT;
    private int players = 2;
    private MctsOptions options = defaultOptions();
    private long seed;
    private NetworkConfig netConfig;
    private RaceTable rows2;
    private Mcts mcts;
    private RaceMove race;
    private Position position;
    private int[] onlyMove;
    private Best best;
    private boolean searching;

    static MctsOptions defaultOptions() {
        return MctsOptions.defaults().withS1(false).withRace(0);
    }

    /**
     * The jump rule of a facade argument: null, empty or {@code adjacent} is {@link RuleSet.JumpRule#ADJACENT},
     * {@code long} is {@link RuleSet.JumpRule#LONG}.
     */
    public static RuleSet.JumpRule parseJump(String text) {
        if (text == null || text.isEmpty() || text.equals("adjacent")) {
            return RuleSet.JumpRule.ADJACENT;
        }
        if (text.equals("long")) {
            return RuleSet.JumpRule.LONG;
        }
        throw new NetFailure("internal", "jump rule must be adjacent or long, got '" + text + "'");
    }

    /** The player count of a facade argument: null, empty or {@code "2"} is 2, {@code "3"} is 3. */
    public static int parsePlayers(String text) {
        if (text == null || text.isEmpty() || text.equals("2")) {
            return 2;
        }
        if (text.equals("3")) {
            return 3;
        }
        throw new NetFailure("internal", "players must be 2 or 3, got '" + text + "'");
    }

    /**
     * Sets the game variant and the search options and drops the tree. {@code options} is a comma separated list of
     * {@code key=value}: {@code cpuct}, {@code ptemp}, {@code noise}, {@code alpha}, {@code temp}, {@code fpu},
     * {@code win1} (0 or 1), {@code reuse} (0 or 1), {@code s1} (0 or 1), {@code race} (0, 1 or 2), {@code ms} (time
     * budget per move, ms); empty or null keeps the defaults. {@code race} and {@code s1} need
     * {@link #setTable} before the first {@link #begin}.
     */
    public void create(RuleSet.JumpRule jumpRule, int playerCount, int sims, int batch, long newSeed, String optionText) {
        MctsOptions o;
        try {
            o = parseOptions(defaultOptions().withSims(sims).withBatch(batch), optionText);
        } catch (IllegalArgumentException e) {
            throw new NetFailure("bad-options", String.valueOf(e.getMessage()));
        }
        jump = jumpRule;
        players = playerCount;
        options = o;
        seed = newSeed;
        dropSearch();
    }

    static MctsOptions parseOptions(MctsOptions base, String text) {
        MctsOptions o = base;
        if (text == null || text.isEmpty()) {
            return o;
        }
        for (String part : text.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("option '" + part + "' is not key=value");
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            try {
                switch (key) {
                    case "cpuct" -> o = o.withCpuct(Double.parseDouble(value));
                    case "ptemp" -> o = o.withPriorTemperature(Double.parseDouble(value));
                    case "noise" -> o = o.withNoise(Double.parseDouble(value));
                    case "alpha" -> o = new MctsOptions(o.sims(), o.batch(), o.cpuct(), o.priorTemperature(), o.noise(),
                            Double.parseDouble(value), o.temperature(), o.fpu(), o.race(), o.raceRows(), o.s1(),
                            o.win1(), o.reuse(), o.millis());
                    case "temp" -> o = o.withTemperature(Double.parseDouble(value));
                    case "fpu" -> o = o.withFpu(Double.parseDouble(value));
                    case "win1" -> o = o.withWin1(flag(key, value));
                    case "reuse" -> o = o.withReuse(flag(key, value));
                    case "s1" -> o = o.withS1(flag(key, value));
                    case "race" -> o = o.withRace(Integer.parseInt(value));
                    case "ms" -> o = o.withMillis(Long.parseLong(value));
                    default -> throw new IllegalArgumentException("unknown option '" + key + "'");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("option " + key + " has the bad value '" + value + "'");
            }
        }
        return o;
    }

    private static boolean flag(String key, String value) {
        if (value.equals("1") || value.equals("true")) {
            return true;
        }
        if (value.equals("0") || value.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("option " + key + " must be 0 or 1, got '" + value + "'");
    }

    /**
     * Tells the engine which network the caller evaluates with: the plane set id from the weights metadata
     * ({@code plain}, {@code classic1}, {@code classic1-planes}, {@code classic1v}) and the width of the value head (2,
     * or 3 for a three-player net and for {@code classic1v}). Drops the tree.
     */
    public void setWeightsInfo(String planesId, int valueWidth) {
        PlaneSet planes;
        try {
            planes = PlaneSet.ofId(planesId);
        } catch (IllegalArgumentException e) {
            throw new NetFailure("bad-weights-info", String.valueOf(e.getMessage()));
        }
        if (valueWidth != 2 && valueWidth != 3 || planes.variants() && valueWidth != 3) {
            throw new NetFailure("bad-weights-info", "value width " + valueWidth + " does not fit planes " + planesId);
        }
        netConfig = new NetworkConfig(1, 0, valueWidth, 1).withPlanes(planes);
        dropSearch();
    }

    /** Floats per encoded position of the weights info (122 * planes). */
    public int inputSize() {
        return config().inputSize();
    }

    /** Value head width of the weights info. */
    public int valueWidth() {
        return config().players();
    }

    /** Installs the rows&gt;=2 race table from the bytes of {@code rows2.bin} (header and body, not verified). */
    public long setTable(byte[] file) {
        RaceTable table;
        try {
            table = RaceTable.fromFileBytes(file);
        } catch (UncheckedIOException e) {
            throw new NetFailure("table-magic", String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        } catch (IllegalArgumentException e) {
            throw new NetFailure("table-magic", String.valueOf(e.getMessage()));
        }
        rows2 = table;
        dropSearch();
        return table.states();
    }

    private NetworkConfig config() {
        if (netConfig == null) {
            throw new NetFailure("no-weights-info", "call setWeightsInfo first");
        }
        return netConfig;
    }

    private void dropSearch() {
        if (mcts != null) {
            mcts.abort();
        }
        mcts = null;
        race = null;
        position = null;
        searching = false;
        best = null;
        onlyMove = null;
    }

    private Mcts mcts() {
        if (mcts == null) {
            NetworkConfig config = config();
            if (!config.planes().variants() && config.players() != players) {
                throw new NetFailure("bad-weights-info", "the network has " + config.players() + " players, the game " + players);
            }
            if ((options.s1() || options.race() > 0) && rows2 == null) {
                throw new NetFailure("no-table", "S1 and race play need the table: call setTable first");
            }
            try {
                mcts = new Mcts(config, options, SearchRandom.portable(seed), options.s1() ? rows2 : null);
            } catch (IllegalArgumentException e) {
                throw new NetFailure("internal", String.valueOf(e.getMessage()));
            }
            race = options.race() > 0 ? new RaceMove(rows2, options.race(), 2) : null;
        }
        return mcts;
    }

    /** The legal moves after {@code history}; empty if the game is over. */
    public int[] legalMoves(int[] history, RuleSet.JumpRule rule, int playerCount) {
        Position p = replay(history, rule, playerCount);
        return p.outcome() != null ? new int[0] : p.legalMoves();
    }

    /** The state of the game after {@code history}: null while it goes on, else the outcome (also a side without a move). */
    public Outcome outcome(int[] history, RuleSet.JumpRule rule, int playerCount) {
        Position p = replay(history, rule, playerCount);
        Outcome outcome = p.outcome();
        if (outcome == null && p.generate(new int[Position.MAX_MOVES]) == 0) {
            return p.noMovesOutcome();
        }
        return outcome;
    }

    /**
     * Starts the search of the position after {@code history} (under the variant of {@link #create}) and asks for the
     * first positions, as {@link #prepare} does.
     *
     * @return the number of positions to evaluate; 0 if the move is known without the network (one legal move, or a
     *         race move); then {@link #best} is ready
     * @throws NetFailure {@code illegal-history} if the game is over after the history or a move is illegal
     */
    public int begin(int[] history) {
        Mcts m = mcts();
        Position p = replay(history, jump, players);
        int[] legal = p.outcome() != null ? new int[0] : p.legalMoves();
        if (legal.length == 0) {
            throw new NetFailure("illegal-history", "the game is over after " + history.length + " plies");
        }
        position = p;
        best = null;
        onlyMove = null;
        searching = false;
        if (race != null && players == 2) {
            int move = race.choose(p);
            if (move >= 0) {
                best = new Best(move, 0, 0, 0, 0, true, new int[0], new int[0], new double[0]);
                return 0;
            }
        }
        if (legal.length == 1) {
            best = new Best(legal[0], 0, 0, 0, 0, false, new int[0], new int[0], new double[0]);
            return 0;
        }
        try {
            m.begin(p);
        } catch (IllegalArgumentException e) {
            throw new NetFailure("internal", String.valueOf(e.getMessage()));
        }
        searching = true;
        return prepare();
    }

    /**
     * The number of positions waiting for the network; 0 when the search is finished. Repeating the call without
     * {@link #apply} returns the same number.
     */
    public int prepare() {
        if (!searching) {
            return 0;
        }
        try {
            int n = mcts.prepare();
            if (n == 0) {
                finish();
            }
            return n;
        } catch (IllegalStateException e) {
            searching = false;
            throw new NetFailure("non-finite", String.valueOf(e.getMessage()));
        }
    }

    /** The encoded positions that {@link #prepare} announced: {@code n * inputSize()} floats, canonical orientation, channels last. */
    public float[] inputs() {
        if (!searching) {
            throw new NetFailure("not-ready", "no positions are waiting for the network");
        }
        return mcts.pendingInputs();
    }

    /**
     * Hands in the outputs of the network for the pending positions: {@code policyFrom} and {@code policyTo} with 121
     * floats per position (the "from" and the "to" logit of each canonical cell), {@code values} with
     * {@code valueWidth()} floats per position (relative players, mover first). Then asks for the next positions
     * as {@link #prepare} does.
     *
     * @return the number of positions to evaluate next; 0 when the search is finished
     */
    public int apply(float[] policyFrom, float[] policyTo, float[] values) {
        if (!searching) {
            throw new NetFailure("not-ready", "no positions are waiting for the network");
        }
        int n = mcts.pendingCount();
        int cells = Encoder.POLICY_SIZE / Encoder.POLICY_CHANNELS;
        if (policyFrom.length != n * cells || policyTo.length != n * cells || values.length != n * config().players()) {
            throw new NetFailure("bad-outputs", "expected policyFrom/policyTo " + n * cells + " and values "
                    + n * config().players() + " floats for " + n + " positions, got " + policyFrom.length + "/"
                    + policyTo.length + "/" + values.length);
        }
        float[] policy = new float[n * Encoder.POLICY_SIZE];
        for (int i = 0; i < n * cells; i++) {
            policy[2 * i + Encoder.FROM] = policyFrom[i];
            policy[2 * i + Encoder.TO] = policyTo[i];
        }
        try {
            mcts.apply(policy, values);
        } catch (IllegalStateException e) {
            searching = false;
            throw new NetFailure("non-finite", String.valueOf(e.getMessage()));
        }
        return prepare();
    }

    private void finish() {
        MctsResult r = mcts.result();
        best = new Best(r.move(), r.rootValue(), r.sims(), r.nodes(), r.exactLeaves(), false, r.moves(), r.visits(),
                r.values());
        searching = false;
    }

    /** The chosen move and the statistics of the finished search. */
    public Best best() {
        if (best == null) {
            throw new NetFailure("not-ready", "the search is not finished");
        }
        return best;
    }

    private static Position replay(int[] history, RuleSet.JumpRule rule, int playerCount) {
        Position p = Position.initial(RuleSet.defaults(playerCount).withJumpRule(rule));
        for (int ply = 0; ply < history.length; ply++) {
            int move = history[ply];
            boolean legal = false;
            for (int m : p.legalMoves()) {
                if (m == move) {
                    legal = true;
                    break;
                }
            }
            if (!legal) {
                throw new NetFailure("illegal-history", "move " + move + " at ply " + ply + " is not legal");
            }
            p.make(move);
        }
        return p;
    }
}
