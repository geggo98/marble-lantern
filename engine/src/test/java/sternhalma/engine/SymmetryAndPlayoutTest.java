package sternhalma.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.Test;

/** Conformance tests 16 and 17 of docs/rules.md (with seeded random playouts instead of jqwik). */
class SymmetryAndPlayoutTest {

    @Test
    void symmetriesAreInvolutionsAndTheRotationIsFlipFollowedByMirror() {
        for (int c = 0; c < Geometry.CELLS; c++) {
            assertEquals(c, Geometry.mirror(Geometry.mirror(c)));
            assertEquals(c, Geometry.playerFlip(Geometry.playerFlip(c)));
            assertEquals(c, Geometry.rotate180(Geometry.rotate180(c)));
            assertEquals(Geometry.rotate180(c), Geometry.mirror(Geometry.playerFlip(c)));
        }
    }

    @Test
    void mirrorKeepsEachCornerAndPlayerFlipSwapsTheTwoPlayersCorners() {
        Layout layout = Layout.of(2);
        for (int c = 0; c < Geometry.CELLS; c++) {
            int corner = Geometry.cornerOf(c);
            if (corner == layout.startCorner(0) || corner == layout.startCorner(1)) {
                assertEquals(corner, Geometry.cornerOf(Geometry.mirror(c)), "mirror keeps the top and bottom corner");
                int other = corner == layout.startCorner(0) ? layout.startCorner(1) : layout.startCorner(0);
                assertEquals(other, Geometry.cornerOf(Geometry.playerFlip(c)), "player flip swaps them");
            }
        }
    }

    private static Set<Integer> mappedMoves(Position pos, IntUnaryOperator map) {
        Set<Integer> result = new HashSet<>();
        for (int m : pos.legalMoves()) {
            result.add(Move.of(map.applyAsInt(Move.from(m)), map.applyAsInt(Move.to(m))));
        }
        return result;
    }

    private static Set<Integer> moveSet(Position pos) {
        Set<Integer> result = new HashSet<>();
        for (int m : pos.legalMoves()) {
            result.add(m);
        }
        return result;
    }

    @Test
    void symmetriesMapLegalPositionsToLegalPositionsWithTheMappedMoves() {
        for (long seed = 1; seed <= 8; seed++) {
            Random random = new Random(seed);
            Position pos = Position.initial(RuleSet.defaults(2).withMoveLimit(400));
            for (int ply = 0; ply < 80; ply++) {
                Position mirrored = pos.transformed(Geometry::mirror, false);
                assertEquals(mappedMoves(pos, Geometry::mirror), moveSet(mirrored), "mirror, seed " + seed + " ply " + ply);
                Position flipped = pos.transformed(Geometry::playerFlip, true);
                assertEquals(mappedMoves(pos, Geometry::playerFlip), moveSet(flipped), "flip, seed " + seed + " ply " + ply);
                Position rotated = pos.transformed(Geometry::rotate180, true);
                assertEquals(pos.legalMoves().length, rotated.legalMoves().length);
                assertEquals(rotated, flipped.transformed(Geometry::mirror, false), "rotation = flip then mirror");
                int[] moves = pos.legalMoves();
                pos.make(moves[random.nextInt(moves.length)]);
            }
        }
    }

    @Test
    void makeThenUnmakeRestoresPositionAndHashAndTheIncrementalHashMatchesARebuild() {
        for (int players = 2; players <= 3; players++) {
            for (long seed = 1; seed <= 8; seed++) {
                Random random = new Random(seed * 17 + players);
                Position pos = Position.initial(RuleSet.defaults(players).withMoveLimit(400));
                for (int ply = 0; ply < 150 && pos.outcome() == null; ply++) {
                    int[] moves = pos.legalMoves();
                    if (moves.length == 0) {
                        break;
                    }
                    Position before = pos.copy();
                    long hash = pos.hash();
                    for (int m : moves) {
                        pos.make(m);
                        pos.unmake();
                        assertEquals(hash, pos.hash());
                        assertEquals(before, pos);
                        assertEquals(before.ply(), pos.ply());
                    }
                    pos.make(moves[random.nextInt(moves.length)]);
                    assertEquals(pos.transformed(c -> c, false).hash(), pos.hash(), "incremental hash vs rebuild");
                    for (int q = 0; q < players; q++) {
                        int[] board = new int[Geometry.CELLS];
                        for (int c = 0; c < Geometry.CELLS; c++) {
                            board[c] = pos.owner(c);
                        }
                        assertEquals(ReferenceGenerator.wins(pos.rules(), board, q), pos.hasWon(q), "win counters, player " + q);
                    }
                }
            }
        }
    }

    @Test
    void everyGeneratedMoveMovesAnOwnPieceToAnEmptyCell() {
        Random random = new Random(99);
        Position pos = Position.initial(RuleSet.defaults(2).withMoveLimit(400));
        for (int ply = 0; ply < 100; ply++) {
            int[] moves = pos.legalMoves();
            for (int m : moves) {
                assertEquals(pos.sideToMove(), pos.owner(Move.from(m)));
                assertEquals(-1, pos.owner(Move.to(m)));
                assertTrue(pos.layout().allowed(pos.sideToMove(), Move.to(m)));
            }
            pos.make(moves[random.nextInt(moves.length)]);
        }
    }
}
