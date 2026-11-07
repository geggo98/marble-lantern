package sternhalma.selfplay;

import sternhalma.selfplay.Adjudication.Firing;
import sternhalma.selfplay.Adjudication.Layer;

/**
 * One self-play game as the tool stores it.
 *
 * @param id             game index (the opening is a function of seed and id)
 * @param openingPlies   number of random opening plies at the start of {@code moves}
 * @param moves          every move played from the start position, including the play-out of a control game
 * @param scores         per move: the mover's search score, or {@link #NO_SCORE} (opening ply, race play)
 * @param label          the layer that set the label, {@link Layer#NONE} if the game was played to its end
 * @param labelPly       ply of the labelled position: the cut, after which a production game stops and no
 *                       sample is written
 * @param labelValues    the label, {@code Outcome.values} layout, absolute players
 * @param playedOut      whether the game was played to its end (always for unadjudicated and control games)
 * @param finalPly       ply at the end of the game, or -1 if not played out
 * @param finalValues    outcome values at the end, or null if not played out
 * @param s1             first S1 firing, or null (in a production game: not reached before the cut)
 * @param margin         first score-margin firing, or null
 * @param eval           first static-evaluation firing, or null
 * @param raceScoresIgnored search scores in the race band seen by layer 2 (each one ignored)
 * @param cpuNanos       thread CPU time of the whole game (opening, search, adjudication, samples)
 * @param searchCpuNanos thread CPU time inside the players' move choice
 * @param s1CpuNanos     thread CPU time inside layers 1 and 3
 * @param nodes          search nodes of both players
 * @param samples        the samples of the game (positions before the cut), or null if samples are off
 */
public record GameRecord(int id, int openingPlies, int[] moves, int[] scores, Layer label, int labelPly,
        double[] labelValues, boolean playedOut, int finalPly, double[] finalValues, Firing s1, Firing margin,
        Firing eval, int raceScoresIgnored, long cpuNanos, long searchCpuNanos, long s1CpuNanos, long nodes,
        Samples samples) {

    /** Marks a move without a search score. */
    public static final int NO_SCORE = Integer.MIN_VALUE;

    /**
     * The samples of one game in the layout of the sample file (canonical cells, see docs/training.md).
     *
     * @param cells       [n * 121] canonical cells: 0 empty, 1 + relative player
     * @param ply         [n]
     * @param moveCounts  [n] legal moves per sample
     * @param moveFrom    canonical from cells of all legal moves
     * @param moveTo      canonical to cells
     * @param moveTarget  policy target per legal move
     * @param played      [n] index of the played move among the sample's legal moves
     * @param value       [n * players] value target, relative players
     * @param policyWeight [n]
     * @param valueWeight  [n]
     * @param flags       [n] bit 0 full-search policy, bit 1 value from classical adjudication, bit 2 random
     *                    opening ply
     */
    public record Samples(byte[] cells, short[] ply, int[] moveCounts, byte[] moveFrom, byte[] moveTo,
            float[] moveTarget, int[] played, float[] value, float[] policyWeight, float[] valueWeight, byte[] flags) {

        public int count() {
            return ply.length;
        }
    }

    /** Plies a production game saves against playing to the end; 0 if not played out or not adjudicated. */
    public int pliesSaved() {
        return playedOut && label != Layer.NONE ? finalPly - labelPly : 0;
    }
}
