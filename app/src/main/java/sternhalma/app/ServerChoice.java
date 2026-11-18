package sternhalma.app;

/** The answer to one search; the fields of {@code ChooseResult} in web/src/engines/engine.ts. */
public record ServerChoice(int move, int score, int depth, long nodes, double ms, boolean raceMove, int rows1Requests) {
}
