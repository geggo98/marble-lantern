package sternhalma.search.contract;

import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.search.Strategy;

/**
 * The contract that every strategy must fulfil. A module that adds a strategy extends this class and returns
 * its strategies; the properties then run against each of them.
 *
 * <ol>
 *   <li>{@link #strategyBeatsARandomPlayer}: against a seeded random player the game is always lost by the
 *       random player, and it lasts at least as many plies as the rules require. If this fails, something is
 *       generally broken.</li>
 *   <li>{@link #selfPlayIsCleanFromAnyLegalPosition}: a strategy playing itself from many legal positions,
 *       including the default start and positions one move before the end, never plays an illegal move and
 *       never fails technically.</li>
 * </ol>
 *
 * <p>The properties use fixed seeds, so every run explores the same cases and a failure is reproducible. The
 * failure message contains everything needed to rebuild the case.
 */
public abstract class StrategyContract {

    /** The strategies under test. */
    protected abstract List<Strategy> strategies();

    /** Number of games per property; subclasses with slow strategies may lower it by overriding. */
    protected int tries() {
        return 100;
    }

    /** The house size of the start positions; a subclass overrides it for the sweep over the 5-row board. */
    protected RuleSet.TriangleRows rows() {
        return RuleSet.TriangleRows.FOUR;
    }

    /** A start position description that can be printed and rebuilt. */
    public record Start(int players, int variant, long seed, int plies, double greed, boolean justBeforeEnd,
            RuleSet.TriangleRows rows) {
        public Start(int players, int variant, long seed, int plies, double greed, boolean justBeforeEnd) {
            this(players, variant, seed, plies, greed, justBeforeEnd, RuleSet.TriangleRows.FOUR);
        }

        public Position position() {
            RuleSet rules = PositionGenerator.rules(players, variant, rows);
            return justBeforeEnd ? PositionGenerator.justBeforeTheEnd(rules, seed)
                    : PositionGenerator.played(rules, seed, plies, greed);
        }
    }

    // ---- generators ----

    /** Start positions for a player count: the default start often, then random, greedy and end-of-game ones. */
    protected Arbitrary<Start> starts(int players) {
        Arbitrary<Integer> variant = Arbitraries.integers().between(0, PositionGenerator.VARIANTS - 1);
        Arbitrary<Long> seed = Arbitraries.longs();
        Arbitrary<Integer> plies = Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.just(0)),
                Tuple.of(5, Arbitraries.integers().between(1, 40)),
                Tuple.of(4, Arbitraries.integers().between(41, 190)));
        Arbitrary<Double> greed = Arbitraries.of(0.0, 0.5, 0.95);
        Arbitrary<Boolean> end = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just(true)), Tuple.of(5, Arbitraries.just(false)));
        return Combinators.combine(variant, seed, plies, greed, end)
                .as((v, s, p, g, e) -> new Start(players, v, s, p, g, e, rows()));
    }

    /** A strategy together with a start position for a player count that it supports. */
    protected Arbitrary<Tuple.Tuple2<Strategy, Start>> strategyAndStart() {
        List<Strategy> all = strategies();
        Assume.that(!all.isEmpty());
        return Arbitraries.of(all).flatMap(strategy ->
                Arbitraries.of(strategy.supportedPlayers().stream().sorted().toList())
                        .flatMap(this::starts)
                        .map(start -> Tuple.of(strategy, start)));
    }

    @Provide
    Arbitrary<Tuple.Tuple2<Strategy, Start>> selfPlayCases() {
        return strategyAndStart();
    }

    /** Opening for the beat-random property: the default start or up to four random plies. */
    @Provide
    Arbitrary<Tuple.Tuple2<Strategy, Start>> beatRandomCases() {
        List<Strategy> strong = strategies().stream().filter(s -> s.mustBeatRandom() && s.supports(2)).toList();
        Assume.that(!strong.isEmpty());
        Arbitrary<Start> opening = Combinators.combine(
                        Arbitraries.longs(), Arbitraries.integers().between(0, 4))
                .as((seed, plies) -> new Start(2, 0, seed, plies, 0.0, false, rows()));
        return Arbitraries.of(strong).flatMap(strategy -> opening.map(start -> Tuple.of(strategy, start)));
    }

    // ---- the contract ----

    @Property(tries = 100, seed = "20261107")
    void strategyBeatsARandomPlayer(
            @ForAll("beatRandomCases") Tuple.Tuple2<Strategy, Start> chosen,
            @ForAll boolean strategyMovesFirst,
            @ForAll long randomSeed) {
        StrategyChecks.assertBeatsRandom(chosen.get1(), chosen.get2().position(), strategyMovesFirst, randomSeed);
    }

    @Property(tries = 120, seed = "20261108")
    void selfPlayIsCleanFromAnyLegalPosition(
            @ForAll("selfPlayCases") Tuple.Tuple2<Strategy, Start> chosen,
            @ForAll long playerSeed) {
        StrategyChecks.assertCleanSelfPlay(chosen.get1(), chosen.get2().position(), playerSeed);
    }
}
