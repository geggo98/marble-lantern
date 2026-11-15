#include "json_min.hpp"

#include <cstddef>
#include <limits>

namespace sh::json {
namespace {

// Reads the bytes of a text through string_view::operator[], which the hardened library checks.
class Parser {
public:
    explicit Parser(std::string_view s) : s_(s) {}

    std::expected<Value, Error> parse_document() {
        skip_ws();
        auto v = parse_value(0);
        if (!v) return v;
        skip_ws();
        if (pos_ != s_.size()) return fail("unexpected content after the top-level value");
        return v;
    }

private:
    std::string_view s_;
    std::size_t pos_ = 0;

    std::unexpected<Error> fail(const std::string& message) const {
        return std::unexpected(Error{"malformed JSON at character " + std::to_string(pos_) + ": " + message});
    }

    int peek() const { return pos_ < s_.size() ? static_cast<unsigned char>(s_[pos_]) : -1; }

    bool consume(char c) {
        if (peek() == static_cast<unsigned char>(c)) {
            ++pos_;
            return true;
        }
        return false;
    }

    void skip_ws() {
        while (pos_ < s_.size()) {
            const char c = s_[pos_];
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            ++pos_;
        }
    }

    std::expected<Value, Error> parse_value(int depth) {
        if (depth > kMaxDepth) return fail("nesting is too deep");
        const int c = peek();
        if (c == '{') return parse_object(depth);
        if (c == '[') return parse_array(depth);
        if (c == '"') {
            auto s = parse_string();
            if (!s) return std::unexpected(s.error());
            return Value{std::move(*s)};
        }
        if (c >= '0' && c <= '9') return parse_uint();
        return fail("expected an object, an array, a string or a non-negative integer");
    }

    std::expected<Value, Error> parse_uint() {
        const std::size_t start = pos_;
        std::uint64_t value = 0;
        while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') {
            const auto digit = static_cast<std::uint64_t>(s_[pos_] - '0');
            if (value > (static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) - digit) / 10) {
                return fail("integer is too large");
            }
            value = value * 10 + digit;
            ++pos_;
        }
        if (pos_ - start > 1 && s_[start] == '0') return fail("integer has a leading zero");
        if (pos_ < s_.size() && (s_[pos_] == '.' || s_[pos_] == 'e' || s_[pos_] == 'E')) {
            return fail("fractions and exponents are not supported");
        }
        return Value{value};
    }

    static int hex_digit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    std::expected<unsigned, Error> parse_hex4() {
        if (s_.size() - pos_ < 4) return fail("truncated \\u escape");
        unsigned value = 0;
        for (int i = 0; i < 4; ++i) {
            const int d = hex_digit(s_[pos_ + static_cast<std::size_t>(i)]);
            if (d < 0) return fail("invalid \\u escape");
            value = value * 16 + static_cast<unsigned>(d);
        }
        pos_ += 4;
        return value;
    }

    static void append_utf8(std::string& out, unsigned cp) {
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }

    std::expected<std::string, Error> parse_string() {
        if (!consume('"')) return fail("expected a string");
        std::string out;
        while (true) {
            if (pos_ >= s_.size()) return fail("unterminated string");
            const char c = s_[pos_++];
            if (c == '"') return out;
            if (static_cast<unsigned char>(c) < 0x20) return fail("unescaped control character in a string");
            if (c != '\\') {
                out += c;  // the text is valid UTF-8 (checked before parsing), so copying bytes keeps it valid
                continue;
            }
            if (pos_ >= s_.size()) return fail("unterminated escape");
            const char e = s_[pos_++];
            switch (e) {
                case '"': case '\\': case '/': out += e; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    auto u = parse_hex4();
                    if (!u) return std::unexpected(u.error());
                    if (*u >= 0xD800 && *u <= 0xDBFF) {
                        if (!s_.substr(pos_).starts_with("\\u")) return fail("unpaired surrogate escape");
                        pos_ += 2;
                        auto low = parse_hex4();
                        if (!low) return std::unexpected(low.error());
                        if (*low < 0xDC00 || *low > 0xDFFF) return fail("unpaired surrogate escape");
                        append_utf8(out, 0x10000 + ((*u - 0xD800) << 10) + (*low - 0xDC00));
                    } else if (*u >= 0xDC00 && *u <= 0xDFFF) {
                        return fail("unpaired surrogate escape");
                    } else {
                        append_utf8(out, *u);
                    }
                    break;
                }
                default: return fail(std::string("invalid escape \\") + e);
            }
        }
    }

    std::expected<Value, Error> parse_array(int depth) {
        ++pos_;  // '['
        Array items;
        skip_ws();
        if (consume(']')) return Value{std::move(items)};
        while (true) {
            skip_ws();
            auto v = parse_value(depth + 1);
            if (!v) return v;
            items.push_back(std::move(*v));
            skip_ws();
            if (consume(',')) continue;
            if (consume(']')) return Value{std::move(items)};
            return fail("expected ',' or ']'");
        }
    }

    std::expected<Value, Error> parse_object(int depth) {
        ++pos_;  // '{'
        Object members;
        skip_ws();
        if (consume('}')) return Value{std::move(members)};
        while (true) {
            skip_ws();
            auto key = parse_string();
            if (!key) return std::unexpected(key.error());
            skip_ws();
            if (!consume(':')) return fail("expected ':' after key " + *key);
            skip_ws();
            auto v = parse_value(depth + 1);
            if (!v) return v;
            members.emplace_back(std::move(*key), std::move(*v));
            skip_ws();
            if (consume(',')) continue;
            if (consume('}')) return Value{std::move(members)};
            return fail("expected ',' or '}'");
        }
    }
};

}  // namespace

bool is_valid_utf8(std::string_view text) {
    std::size_t i = 0;
    const auto byte_at = [&](std::size_t k) { return static_cast<unsigned>(static_cast<unsigned char>(text[k])); };
    while (i < text.size()) {
        const unsigned b = byte_at(i);
        std::size_t need = 0;
        unsigned lo = 0x80;
        unsigned hi = 0xBF;
        if (b < 0x80) {
            ++i;
            continue;
        } else if (b >= 0xC2 && b <= 0xDF) {
            need = 1;
        } else if (b == 0xE0) {
            need = 2; lo = 0xA0;
        } else if (b == 0xED) {
            need = 2; hi = 0x9F;  // no surrogates
        } else if (b >= 0xE1 && b <= 0xEF) {
            need = 2;
        } else if (b == 0xF0) {
            need = 3; lo = 0x90;
        } else if (b >= 0xF1 && b <= 0xF3) {
            need = 3;
        } else if (b == 0xF4) {
            need = 3; hi = 0x8F;  // nothing above U+10FFFF
        } else {
            return false;
        }
        if (text.size() - i <= need) return false;
        const unsigned second = byte_at(i + 1);
        if (second < lo || second > hi) return false;
        for (std::size_t k = 2; k <= need; ++k) {
            const unsigned cont = byte_at(i + k);
            if (cont < 0x80 || cont > 0xBF) return false;
        }
        i += need + 1;
    }
    return true;
}

std::expected<Value, Error> parse(std::string_view text) {
    if (!is_valid_utf8(text)) return std::unexpected(Error{"text is not valid UTF-8"});
    return Parser(text).parse_document();
}

}  // namespace sh::json
