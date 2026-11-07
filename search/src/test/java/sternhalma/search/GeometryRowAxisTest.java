package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Geometry;
import sternhalma.engine.Layout;
import sternhalma.tablebase.Rows;

/**
 * The geometric preconditions of the S1 soundness statement ({@link RaceAdjudication}), checked exhaustively for
 * both players' orientations over every cell the player may stand on.
 */
class GeometryRowAxisTest {

    @Test
    void stepsChangeTheRowByAtMostOneAndJumpsByAtMostTwoWithTheHurdleOneRowShort() {
        Layout layout = Layout.of(2);
        int steps = 0;
        int jumps = 0;
        for (int player = 0; player < 2; player++) {
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (!layout.allowed(player, c)) {
                    continue;
                }
                int row = Rows.row(player, c);
                for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                    int n = Geometry.neighbor(c, d);
                    if (n < 0) {
                        continue;
                    }
                    int dn = Rows.row(player, n) - row;
                    assertTrue(Math.abs(dn) <= 1, "step from " + c + " changes the row by " + dn);
                    steps++;
                    int land = Geometry.neighbor(n, d);
                    if (land < 0) {
                        continue;
                    }
                    int dl = Rows.row(player, land) - row;
                    assertTrue(dl == -2 || dl == 0 || dl == 2, "jump from " + c + " changes the row by " + dl);
                    assertEquals(dl / 2, dn, "the hurdle lies half way");
                    jumps++;
                }
            }
        }
        assertEquals(896, steps, "2 players x 81 allowed cells x neighbours on the board");
        assertEquals(812, jumps);
    }

    @Test
    void everyAllowedCellBelowTheTipHasAnAllowedNeighbourOnTheNextRow() {
        Layout layout = Layout.of(2);
        for (int player = 0; player < 2; player++) {
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (!layout.allowed(player, c) || Rows.row(player, c) >= Rows.LAST_ROW) {
                    continue;
                }
                boolean found = false;
                for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                    int n = Geometry.neighbor(c, d);
                    if (n >= 0 && layout.allowed(player, n) && Rows.row(player, n) == Rows.row(player, c) + 1) {
                        found = true;
                    }
                }
                assertTrue(found, "player " + player + " cell " + c + " row " + Rows.row(player, c));
                // the same backwards, for the loser who moves away from its goal
                found = false;
                if (Rows.row(player, c) > -Rows.LAST_ROW) {
                    for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                        int n = Geometry.neighbor(c, d);
                        if (n >= 0 && layout.allowed(player, n) && Rows.row(player, n) == Rows.row(player, c) - 1) {
                            found = true;
                        }
                    }
                    assertTrue(found, "backwards from cell " + c);
                }
            }
        }
    }

    @Test
    void fewerThanTenCellsLieBehindEachGoalsFrontRowSoNoOpponentPieceCanStandInAGoalOfAPassedPosition() {
        Layout layout = Layout.of(2);
        for (int player = 0; player < 2; player++) {
            int goalCells = 0;
            int behindFront = 0;
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (Geometry.cornerOf(c) == layout.goalCorner(player)) {
                    goalCells++;
                    assertTrue(Rows.row(player, c) >= Rows.GOAL_ROW && Rows.row(player, c) <= Rows.LAST_ROW);
                }
                if (layout.allowed(player, c) && Rows.row(player, c) > Rows.GOAL_ROW) {
                    behindFront++;
                }
            }
            assertEquals(10, goalCells);
            assertEquals(6, behindFront, "cells on rows 6 to 8");
            assertTrue(behindFront < 10);
            // and all cells of rows 5 to 8 that the player may stand on are goal cells
            for (int c = 0; c < Geometry.CELLS; c++) {
                if (layout.allowed(player, c) && Rows.row(player, c) >= Rows.GOAL_ROW) {
                    assertEquals(layout.goalCorner(player), Geometry.cornerOf(c));
                }
                if (layout.allowed(player, c) && Rows.row(player, c) <= -Rows.GOAL_ROW) {
                    assertEquals(layout.startCorner(player), Geometry.cornerOf(c));
                }
            }
        }
    }

    @Test
    void jumpsKeepTheParityClassAndStepsChangeIt() {
        for (int c = 0; c < Geometry.CELLS; c++) {
            for (int d = 0; d < Geometry.DIRECTIONS; d++) {
                int n = Geometry.neighbor(c, d);
                if (n < 0) {
                    continue;
                }
                assertTrue(((Geometry.q(c) ^ Geometry.q(n)) & 1) != 0 || ((Geometry.r(c) ^ Geometry.r(n)) & 1) != 0,
                        "a step changes (q mod 2, r mod 2)");
                int land = Geometry.neighbor(n, d);
                if (land >= 0) {
                    assertEquals(Geometry.q(c) & 1, Geometry.q(land) & 1);
                    assertEquals(Geometry.r(c) & 1, Geometry.r(land) & 1);
                }
            }
        }
    }
}
