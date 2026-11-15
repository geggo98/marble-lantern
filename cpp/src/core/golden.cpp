#include "golden.hpp"

#include <cstdlib>
#include <fstream>
#include <iterator>
#include <span>
#include <sstream>

#include "sha256.hpp"

#ifndef SH_GOLDEN_DIR
#define SH_GOLDEN_DIR "golden"
#endif

namespace sh::golden {
namespace {

std::vector<std::string_view> split(std::string_view text, char sep) {
    std::vector<std::string_view> parts;
    std::size_t start = 0;
    while (true) {
        const std::size_t at = text.find(sep, start);
        if (at == std::string_view::npos) {
            parts.push_back(text.substr(start));
            return parts;
        }
        parts.push_back(text.substr(start, at - start));
        start = at + 1;
    }
}

std::expected<std::string, Error> read_file(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return std::unexpected(Error{ErrorKind::Missing, "cannot open " + path.filename().string()});
    return std::string(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
}

}  // namespace

std::filesystem::path directory() {
    if (const char* env = std::getenv("STERNHALMA_GOLDEN_DIR"); env != nullptr && *env != '\0') return env;
    return SH_GOLDEN_DIR;
}

bool required() {
    const char* env = std::getenv("STERNHALMA_GOLDEN_REQUIRED");
    return env != nullptr && std::string_view(env) == "1";
}

std::expected<std::vector<ManifestEntry>, Error> parse_manifest(std::string_view text) {
    std::vector<ManifestEntry> entries;
    std::size_t line_no = 0;
    for (std::string_view line : split(text, '\n')) {
        ++line_no;
        if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
        if (line.empty() || line.front() == '#' || line.starts_with("file\t")) continue;
        const std::vector<std::string_view> f = split(line, '\t');
        if (f.size() < 3) {
            return std::unexpected(Error{ErrorKind::Corrupt, "MANIFEST.tsv line " + std::to_string(line_no) + ": expected name, lines, sha256"});
        }
        ManifestEntry e;
        e.name = std::string(f[0]);
        std::size_t used = 0;
        try {
            e.lines = std::stoul(std::string(f[1]), &used);
        } catch (const std::exception&) {
            used = 0;
        }
        if (used == 0 || used != f[1].size()) {
            return std::unexpected(Error{ErrorKind::Corrupt, "MANIFEST.tsv line " + std::to_string(line_no) + ": bad line count"});
        }
        e.sha256 = std::string(f[2]);
        if (f.size() > 3) e.commit = std::string(f[3]);
        entries.push_back(std::move(e));
    }
    return entries;
}

std::expected<std::vector<std::string>, Error> load_lines(const std::filesystem::path& dir, std::string_view name) {
    auto manifest_text = read_file(dir / "MANIFEST.tsv");
    if (!manifest_text) return std::unexpected(Error{ErrorKind::Missing, "no MANIFEST.tsv in the golden directory (run the Java exporter)"});
    auto manifest = parse_manifest(*manifest_text);
    if (!manifest) return std::unexpected(manifest.error());
    const ManifestEntry* entry = nullptr;
    for (const ManifestEntry& e : *manifest) {
        if (e.name == name) entry = &e;
    }
    if (entry == nullptr) return std::unexpected(Error{ErrorKind::Missing, "not listed in MANIFEST.tsv"});
    auto content = read_file(dir / std::string(name));
    if (!content) return std::unexpected(Error{ErrorKind::Corrupt, "listed in MANIFEST.tsv but the file cannot be read"});
    const std::string actual = sha256_hex(std::as_bytes(std::span<const char>(*content)));
    if (actual != entry->sha256) {
        return std::unexpected(Error{ErrorKind::Corrupt, "SHA-256 differs from MANIFEST.tsv (expected " + entry->sha256 + ", got " + actual + ")"});
    }
    std::vector<std::string> lines;
    std::istringstream in(*content);
    for (std::string line; std::getline(in, line);) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        lines.push_back(std::move(line));
    }
    if (lines.size() != entry->lines) {
        return std::unexpected(Error{ErrorKind::Corrupt, "line count " + std::to_string(lines.size()) + " differs from MANIFEST.tsv (" + std::to_string(entry->lines) + ")"});
    }
    return lines;
}

std::expected<std::vector<std::string>, Error> load_lines(std::string_view name) { return load_lines(directory(), name); }

}  // namespace sh::golden
