#include "position.hpp"

#include <algorithm>
#include <exception>
#include <utility>

#include "splittable_random.hpp"

namespace sh {

namespace {

constexpr std::size_t sz(int i) { return static_cast<std::size_t>(i); }

// A corner index that no cell has (corner indices are -1 to 5).
constexpr int kNoCorner = -2;

struct Keys {
    std::array<std::array<std::uint64_t, kCells>, 3> piece{};
    std::array<std::uint64_t, 3> side{};
    Keys() {
        SplittableRandom random(0x5E7A11A100000001ULL);
        for (int p = 0; p < 3; ++p) {
            side[sz(p)] = random.next_long();
            for (int c = 0; c < kCells; ++c) piece[sz(p)][sz(c)] = random.next_long();
        }
    }
};

// Built once at program start, before main; the hot paths read it without a guard.
const Keys kKeys;

std::uint64_t bit_of(int cell) { return std::uint64_t{1} << (cell & 63); }  // Java: 1L << cell uses the shift count mod 64

}  // namespace

std::uint64_t Position::side_key(int player) { return kKeys.side[sz(player)]; }
std::uint64_t Position::piece_key(int player, int cell) { return kKeys.piece[sz(player)][sz(cell)]; }

Position::Position(const RuleSet& rules)
    : rules_(rules), long_jump_(rules.jump_rule() == RuleSet::JumpRule::Long), hash_(kKeys.side[0]), moves_(65), hashes_(65) {
    owner_.fill(-1);
}

Position Position::empty(const RuleSet& rules) { return Position(rules); }

Position Position::initial(const RuleSet& rules) {
    Position position(rules);
    for (int p = 0; p < rules.players(); ++p) {
        for (const int cell : geometry::corner_cells(position.layout().start_corner(p))) {
            if (!position.put(p, cell).has_value()) std::terminate();  // the start corner is allowed, empty and has room: cannot fail
        }
    }
    return position;
}

Position Position::copy() const {
    Position c(rules_);
    c.owner_ = owner_;
    c.goal_occupied_ = goal_occupied_;
    c.goal_own_ = goal_own_;
    c.piece_cells_ = piece_cells_;
    c.piece_count_ = piece_count_;
    c.slot_ = slot_;
    c.side_ = side_;
    c.ply_ = ply_;
    c.hash_ = hash_;
    c.moves_ = moves_;
    c.hashes_ = hashes_;
    return c;
}

// ---- setup ----

std::expected<void, std::string> Position::put(int player, int cell) {
    if (ply_ > 0) return std::unexpected("setup is only allowed before the first move (ply " + std::to_string(ply_) + ")");
    if (player < 0 || player >= rules_.players()) return std::unexpected("player " + std::to_string(player) + " outside 0..1");
    if (cell < 0 || cell >= kCells) return std::unexpected("no such cell: " + std::to_string(cell));
    if (!layout().allowed(player, cell)) {
        return std::unexpected("player " + std::to_string(player) + " may not enter cell " + std::to_string(cell));
    }
    if (owner_[sz(cell)] >= 0) return std::unexpected("cell " + std::to_string(cell) + " is occupied");
    if (piece_count_[sz(player)] >= kPieces) {
        return std::unexpected("player " + std::to_string(player) + " already has " + std::to_string(kPieces) + " pieces");
    }
    owner_[sz(cell)] = static_cast<std::int8_t>(player);
    hash_ ^= kKeys.piece[sz(player)][sz(cell)];
    update_goal_counts(player, cell, +1);
    insert_piece(player, cell);
    return {};
}

std::expected<void, std::string> Position::put(int player, int q, int r) {
    const int cell = geometry::id(q, r);
    if (cell < 0) return std::unexpected("(" + std::to_string(q) + "," + std::to_string(r) + ") is off the board");
    return put(player, cell);
}

std::expected<void, std::string> Position::set_side_to_move(int player) {
    if (ply_ > 0) return std::unexpected("setup is only allowed before the first move (ply " + std::to_string(ply_) + ")");
    if (player < 0 || player >= rules_.players()) return std::unexpected("player " + std::to_string(player) + " outside 0..1");
    hash_ ^= kKeys.side[sz(side_)] ^ kKeys.side[sz(player)];
    side_ = player;
    return {};
}

// Inserts a cell into the sorted piece list of a player (setup only).
void Position::insert_piece(int player, int cell) {
    const int n = piece_count_[sz(player)];
    auto& cells = piece_cells_[sz(player)];
    int i = n;
    while (i > 0 && cells[sz(i - 1)] > cell) {
        cells[sz(i)] = cells[sz(i - 1)];
        slot_[sz(cells[sz(i)])] = i;
        --i;
    }
    cells[sz(i)] = cell;
    slot_[sz(cell)] = i;
    piece_count_[sz(player)] = n + 1;
}

// Replaces `from` by `to` in the sorted piece list of a player, keeping it sorted.
void Position::move_piece(int player, int from, int to) {
    auto& cells = piece_cells_[sz(player)];
    int i = slot_[sz(from)];
    if (to > from) {
        const int last = piece_count_[sz(player)] - 1;
        while (i < last && cells[sz(i + 1)] < to) {
            const int c = cells[sz(i + 1)];
            cells[sz(i)] = c;
            slot_[sz(c)] = i;
            ++i;
        }
    } else {
        while (i > 0 && cells[sz(i - 1)] > to) {
            const int c = cells[sz(i - 1)];
            cells[sz(i)] = c;
            slot_[sz(c)] = i;
            --i;
        }
    }
    cells[sz(i)] = to;
    slot_[sz(to)] = i;
}

int Position::distance_sum(int player) const {
    int sum = 0;
    const auto cells = pieces(player);
    for (const int c : cells) sum += layout().distance(player, c);
    return sum;
}

// ---- make / unmake ----

void Position::make(int move) {
    const int from = Move::from(move);
    const int to = Move::to(move);
    const int p = side_;
    if (sz(ply_ + 1) >= hashes_.size()) {
        hashes_.resize(hashes_.size() * 2);
        moves_.resize(moves_.size() * 2);
    }
    hashes_[sz(ply_)] = hash_;
    moves_[sz(ply_)] = move;
    owner_[sz(from)] = -1;
    owner_[sz(to)] = static_cast<std::int8_t>(p);
    hash_ ^= kKeys.piece[sz(p)][sz(from)] ^ kKeys.piece[sz(p)][sz(to)];
    update_goal_counts(p, from, -1);
    update_goal_counts(p, to, +1);
    move_piece(p, from, to);
    const int next = (p + 1) % rules_.players();
    hash_ ^= kKeys.side[sz(p)] ^ kKeys.side[sz(next)];
    side_ = next;
    ++ply_;
    hashes_[sz(ply_)] = hash_;
}

void Position::unmake() {
    if (ply_ == 0) return;
    --ply_;
    const int move = moves_[sz(ply_)];
    const int from = Move::from(move);
    const int to = Move::to(move);
    const int p = (side_ + rules_.players() - 1) % rules_.players();
    owner_[sz(to)] = -1;
    owner_[sz(from)] = static_cast<std::int8_t>(p);
    update_goal_counts(p, to, -1);
    update_goal_counts(p, from, +1);
    move_piece(p, to, from);
    side_ = p;
    hash_ = hashes_[sz(ply_)];
}

void Position::update_goal_counts(int player, int cell, int delta) {
    const int q = layout().goal_player_of_cell(cell);
    if (q >= 0) {
        goal_occupied_[sz(q)] += delta;
        if (q == player) goal_own_[sz(q)] += delta;
    }
}

// ---- legal moves ----

int Position::generate(std::span<int, kMaxMoves> out) {
    const int p = side_;
    const Layout::Table steps = layout().step_table(p);
    const Layout::Table jumps = layout().jump_table(p);
    const int goal = layout().goal_corner(p);
    const bool leave_forbidden = rules_.leave_goal() == RuleSet::LeaveGoal::Forbidden;
    // A move can only be forbidden when an opponent's goal already holds 9 of its 10 cells: a move adds at most one piece to another goal.
    const bool suicide_check = rules_.suicide_moves() == RuleSet::SuicideMoves::Illegal && opponent_goal_nearly_full(p);
    const int pieces_n = piece_count_[sz(p)];
    int count = 0;
    for (int i = 0; i < pieces_n; ++i) {
        const int from = piece_cells_[sz(p)][sz(i)];
        const bool stay_in_goal = leave_forbidden && geometry::corner_of(from) == goal;
        owner_[sz(from)] = -1;  // the moving piece leaves its cell, so the cell counts as empty during jumps
        const int base = from * kDirections;
        for (int d = 0; d < kDirections; ++d) {
            const int n = steps[sz(base + d)];
            if (n >= 0 && owner_[sz(n)] < 0 && (!stay_in_goal || geometry::corner_of(n) == goal) &&
                (!suicide_check || !hands_opponent_the_win(p, from, n))) {
                out[sz(count++)] = Move::of(from, n);
            }
        }
        const int required_corner = stay_in_goal ? goal : kNoCorner;
        count = long_jump_ ? add_long_jumps(p, from, out, count, required_corner, suicide_check)
                           : add_jumps(p, from, out, count, jumps, required_corner, suicide_check);
        owner_[sz(from)] = static_cast<std::int8_t>(p);
    }
    return count;
}

bool Position::opponent_goal_nearly_full(int p) const {
    for (int q = 0; q < rules_.players(); ++q) {
        if (q != p && goal_occupied_[sz(q)] >= kCornerSize - 1) return true;
    }
    return false;
}

// Depth-first search over jump landings; the visited set deduplicates (start, end) pairs. It is a 128-bit set in two words
// (cells 0 to 120); a shift by `cell` uses `cell mod 64`, as in Java. required_corner is the goal corner if the piece may not
// leave it, else kNoCorner.
int Position::add_jumps(int p, int from, std::span<int, kMaxMoves> out, int count_in, Layout::Table jumps, int required_corner,
                        bool suicide_check) {
    int count = count_in;
    std::uint64_t low = 0;
    std::uint64_t high = 0;
    if (from < 64) {
        low = bit_of(from);
    } else {
        high = bit_of(from);
    }
    const auto steps = geometry::step_table();
    int top = 0;
    stack_[sz(top++)] = from;
    while (top > 0) {
        const int c = stack_[sz(--top)];
        const int base = c * kDirections;
        for (int d = 0; d < kDirections; ++d) {
            const int land = jumps[sz(base + d)];  // -1 if off the board or not allowed
            if (land < 0 || owner_[sz(steps[sz(base + d)])] < 0 || owner_[sz(land)] >= 0) continue;
            const std::uint64_t bit = bit_of(land);
            if (land < 64) {
                if ((low & bit) != 0) continue;
                low |= bit;
            } else {
                if ((high & bit) != 0) continue;
                high |= bit;
            }
            stack_[sz(top++)] = land;
            if ((required_corner == kNoCorner || geometry::corner_of(land) == required_corner) &&
                (!suicide_check || !hands_opponent_the_win(p, from, land))) {
                out[sz(count++)] = Move::of(from, land);
            }
        }
    }
    return count;
}

// The long-hop variant of add_jumps (JumpRule::Long): the same depth-first search, visited set and order, another landing rule (see
// long_landing). There is at most one landing per direction; for k = 1 it is the single jump of add_jumps.
int Position::add_long_jumps(int p, int from, std::span<int, kMaxMoves> out, int count_in, int required_corner, bool suicide_check) {
    int count = count_in;
    std::uint64_t low = 0;
    std::uint64_t high = 0;
    if (from < 64) {
        low = bit_of(from);
    } else {
        high = bit_of(from);
    }
    int top = 0;
    stack_[sz(top++)] = from;
    while (top > 0) {
        const int c = stack_[sz(--top)];
        for (int d = 0; d < kDirections; ++d) {
            const int land = long_landing(p, c, d);
            if (land < 0) continue;
            const std::uint64_t bit = bit_of(land);
            if (land < 64) {
                if ((low & bit) != 0) continue;
                low |= bit;
            } else {
                if ((high & bit) != 0) continue;
                high |= bit;
            }
            stack_[sz(top++)] = land;
            if ((required_corner == kNoCorner || geometry::corner_of(land) == required_corner) &&
                (!suicide_check || !hands_opponent_the_win(p, from, land))) {
                out[sz(count++)] = Move::of(from, land);
            }
        }
    }
    return count;
}

// The landing of the long hop from cell c in direction d, or -1. The piece walks over empty cells to the first piece P, k steps away
// (k >= 1); the landing is the cell k steps behind P (the mirror of c across P). The k - 1 cells between c and P, the k - 1 cells
// between P and the landing and the landing itself must be empty and on the board, and player p must be allowed on the landing.
int Position::long_landing(int p, int c, int d) const {
    const auto steps = geometry::step_table();
    int over = c;
    int k = 0;
    do {
        over = steps[sz(over * kDirections + d)];
        ++k;
        if (over < 0) return -1;
    } while (owner_[sz(over)] < 0);
    int land = over;
    for (int i = 0; i < k; ++i) {
        land = steps[sz(land * kDirections + d)];
        if (land < 0 || owner_[sz(land)] >= 0) return -1;
    }
    return layout().allowed(p, land) ? land : -1;
}

// Whether the move would meet the winning condition of another player.
bool Position::hands_opponent_the_win(int p, int from, int to) const {
    const int from_goal = layout().goal_player_of_cell(from);
    const int to_goal = layout().goal_player_of_cell(to);
    for (int q = 0; q < rules_.players(); ++q) {
        if (q == p) continue;
        const int occupied = goal_occupied_[sz(q)] - (from_goal == q ? 1 : 0) + (to_goal == q ? 1 : 0);
        if (is_win(occupied, goal_own_[sz(q)])) return true;
    }
    return false;
}

// ---- end of game ----

// Definition 1 (or the plain rule with BlockadeRule::None): goal full, with an own piece.
bool Position::is_win(int occupied, int own) const {
    if (occupied != kCornerSize) return false;
    return rules_.blockade_rule() == RuleSet::BlockadeRule::OpponentFills ? own >= 1 : own == kCornerSize;
}

std::optional<Outcome> Position::outcome() const {
    const int players = rules_.players();
    for (int i = 0; i < players; ++i) {
        const int q = (side_ + i) % players;  // starts with the player who did not just move
        if (has_won(q)) return Outcome::win(q);
    }
    if (rules_.repetition() == RuleSet::Repetition::RepetitionIsDraw && is_repeated()) {
        return Outcome::no_winner(Outcome::Reason::Repetition, draw_values());
    }
    if (ply_ >= rules_.move_limit()) return Outcome::no_winner(Outcome::Reason::MoveLimit, draw_values());
    return std::nullopt;
}

Outcome Position::no_moves_outcome() const { return Outcome::no_winner(Outcome::Reason::NoMoves, draw_values()); }

bool Position::is_repeated() const {
    for (int k = 0; k < ply_; ++k) {
        if (hashes_[sz(k)] == hash_) return true;
    }
    return false;
}

// Values for a game without a winner, as set by draw_handling.
std::array<double, 2> Position::draw_values() const {
    constexpr int players = 2;
    std::array<double, 2> values{};
    if (rules_.draw_handling() == RuleSet::DrawHandling::Draw) return values;
    double mean = 0;
    std::array<int, players> sums{};
    int max_distance = 0;
    for (int p = 0; p < players; ++p) {
        sums[sz(p)] = distance_sum(p);
        mean += sums[sz(p)];
        max_distance = std::max(max_distance, layout().max_distance(p));
    }
    mean /= players;
    const double scale = static_cast<double>(kPieces) * max_distance;  // no player can be farther than this from the goal
    for (int p = 0; p < players; ++p) values[sz(p)] = (mean - sums[sz(p)]) / scale;
    return values;
}

}  // namespace sh
