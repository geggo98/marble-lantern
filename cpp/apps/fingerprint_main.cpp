// Prints the lines of Java Fingerprint.main for the player specs given (default: R2, R3 and BEST). Compare with the golden files:
//   diff cpp/golden/fingerprint-r2.txt <(build/release-hardened/fingerprint "<R2 spec>")
// Race play (WP6) is not ported: for a spec with race != 0 the output stops after the positions checksum with the line
// "  race play not ported" and the exit code is 3.
#include <iostream>
#include <string>
#include <vector>

#include "args.hpp"
#include "fingerprint.hpp"
#include "player_spec.hpp"

int main(int argc, char** argv) {
    const std::vector<std::string_view> args = sh::args_from_main(argc, argv);
    std::vector<std::string> specs;
    if (args.size() <= 1) {
        specs = {std::string(sh::fingerprint::kR2Spec), std::string(sh::fingerprint::kR3Spec), std::string(sh::fingerprint::kBestSpec)};
    } else {
        for (std::size_t i = 1; i < args.size(); ++i) specs.emplace_back(args[i]);
    }
    const auto reference = sh::fingerprint::load_reference();
    if (!reference) {
        std::cerr << "fingerprint: " << reference.error() << '\n';
        return 2;
    }
    int status = 0;
    for (const std::string& spec : specs) {
        std::cout << "spec " << spec << '\n';
        const auto parsed = sh::PlayerSpec::parse(spec);
        if (!parsed) {
            std::cerr << "fingerprint: " << parsed.error() << '\n';
            return 2;
        }
        const auto positions = sh::fingerprint::positions(*reference, spec);
        if (!positions) {
            std::cerr << "fingerprint: " << positions.error() << '\n';
            return 2;
        }
        for (const std::string& line : positions->lines) std::cout << "  " << line << '\n';
        std::cout << "  positions checksum " << positions->checksum_text() << '\n';
        if (parsed->race != 0) {
            std::cout << "  race play not ported\n";
            status = 3;
            continue;
        }
        for (const std::int64_t nodes : sh::fingerprint::kGameNodes) {
            const auto game = sh::fingerprint::game(*reference, spec, nodes);
            if (!game) {
                std::cerr << "fingerprint: " << game.error() << '\n';
                return 2;
            }
            for (const std::string& line : game->lines) std::cout << "  game@" << nodes << ' ' << line << '\n';
            std::cout << "  game@" << nodes << " checksum " << game->checksum_text() << '\n';
        }
    }
    return status;
}
