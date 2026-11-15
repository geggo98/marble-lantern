// Golden-file reader. The Java exporters write cpp/golden/ (git-ignored); every export also writes MANIFEST.tsv:
//   header line:  file<TAB>lines<TAB>sha256<TAB>commit
//   one line per file: <name><TAB><line count><TAB><SHA-256 of the file, lower-case hex><TAB><git commit of the exporter>
// (lines starting with '#' are comments). load() checks the file against the manifest before it returns any line.
//
// Directory: environment variable STERNHALMA_GOLDEN_DIR, else the directory compiled in by CMake (<repo>/cpp/golden).
// A missing manifest or a file that the manifest does not list is "missing": the test skips with a message, or fails if
// STERNHALMA_GOLDEN_REQUIRED=1. A file whose SHA-256 or line count differs from the manifest is "corrupt" and is always an error.
#pragma once

#include <cstdint>
#include <expected>
#include <filesystem>
#include <string>
#include <string_view>
#include <vector>

namespace sh::golden {

enum class ErrorKind : std::uint8_t { Missing, Corrupt };

struct Error {
    ErrorKind kind = ErrorKind::Missing;
    std::string message;
};

struct ManifestEntry {
    std::string name;
    std::size_t lines = 0;
    std::string sha256;
    std::string commit;
};

std::filesystem::path directory();
bool required();  // STERNHALMA_GOLDEN_REQUIRED=1

// Parses the text of a MANIFEST.tsv (exposed for tests).
std::expected<std::vector<ManifestEntry>, Error> parse_manifest(std::string_view text);

// All lines of a golden file (without line terminators), verified against the manifest in `dir`.
std::expected<std::vector<std::string>, Error> load_lines(const std::filesystem::path& dir, std::string_view name);
std::expected<std::vector<std::string>, Error> load_lines(std::string_view name);

// For tests: the lines, or std::nullopt after the test was marked skipped (not required) or failed (required or corrupt).
// Defined in golden_test_support.hpp to keep this header free of the test harness.

}  // namespace sh::golden
