package sternhalma.selfplay;

import sternhalma.engine.RuleSet.JumpRule;

/**
 * One of the four game variants of the shared network: two or three players, each with the jump rule ADJACENT or LONG
 * (docs/training.md, "Mixed-variant training (shared net)"). The order is the order of reports and of the strata.
 */
public enum Variant {
    TWO_ADJACENT(2, JumpRule.ADJACENT, "2p-adjacent"),
    TWO_LONG(2, JumpRule.LONG, "2p-long"),
    THREE_ADJACENT(3, JumpRule.ADJACENT, "3p-adjacent"),
    THREE_LONG(3, JumpRule.LONG, "3p-long");

    private final int players;
    private final JumpRule jumpRule;
    private final String label;

    Variant(int players, JumpRule jumpRule, String label) {
        this.players = players;
        this.jumpRule = jumpRule;
        this.label = label;
    }

    public int players() {
        return players;
    }

    public JumpRule jumpRule() {
        return jumpRule;
    }

    /** The name used in reports and in {@code metrics.jsonl}, for example {@code 2p-adjacent}. */
    public String label() {
        return label;
    }

    /** The variant of a player count (2 or 3) and a jump rule. */
    public static Variant of(int players, JumpRule jumpRule) {
        for (Variant v : values()) {
            if (v.players == players && v.jumpRule == jumpRule) {
                return v;
            }
        }
        throw new IllegalArgumentException("no variant for " + players + " players and jump rule " + jumpRule);
    }
}
