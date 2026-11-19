# Supply-chain policy

TL;DR: no dependency is installed or proposed until its release is 14 days old, and dependency install scripts do not run. The rules live in files of this repository, so they hold for every developer and for CI, not only for one person's global setup.

Origin: the project owner's global setup already sets `min-release-age=14` and `ignore-scripts=true` in `~/.npmrc` and `exclude-newer = "14 days"` in `~/.config/uv/uv.toml`. The project adopts the same values (request of 2026-11-19). Rationale: a hijacked or broken release is usually found and pulled within days, so a waiting period removes most of the exposure; install scripts are the usual way a hijacked npm package runs code on install.

## What the policy covers

| Area | Rule | Where it is set |
|---|---|---|
| npm in `web/`, `app/` and the repository root | `min-release-age=14` (days) and `ignore-scripts=true` | `.npmrc` in each of the three directories |
| npm versions | Every version in `package.json` is exact (no `^`, `~`, `latest`); `package-lock.json` is committed | `web/package.json`, `app/package.json` |
| uv scripts | Each script with a PEP 723 header has `exclude-newer = "2026-11-01T00:00:00Z"` under `[tool.uv]` and a lock file next to it (`<script>.lock`, from `uv lock --script`) | `scripts/offline/pack-tables.py`, `scripts/cloud/spot-retry.py`, `cpp/scripts/book-nn.py`, `book-aws.py`, `bench-nn-mac.py` |
| Dependabot | `cooldown.default-days: 14` for GitHub Actions, npm `/web` and npm `/app` | `.github/dependabot.yml` |
| GitHub Actions | Pinned to a commit SHA with a `# vX.Y.Z` comment | `.github/workflows/*.yml` |

Limits:

- `min-release-age` needs npm 11.10 or newer. The devenv Node (24) ships npm 11.19.0 and honors it (`npm config get min-release-age` prints `14`). An older npm ignores the key silently, so do not run the project with an npm from outside devenv.
- `min-release-age` is relative, so it moves with the calendar. The uv `exclude-newer` date is absolute, because `uv lock --script` needs a fixed value. Move the date forward when a script's dependencies change; keep it at least 14 days in the past.
- Dependabot's cooldown delays version updates only. Security updates (alerts) are opened at once; that is intended.
- `ignore-scripts=true` can break a package that needs its install script (native builds). No such package is in use today. If one is added, run its script by hand and write down why here.

## What the policy does not cover

- **Gradle dependencies.** There is no cooldown. They are protected by the checksums in `gradle/verification-metadata.xml`: a changed artifact fails the build, but a freshly published version that is entered into the file is accepted.
- **devenv and Nix inputs.** `devenv.lock` pins them. They change only on a manual `devenv update`; no age check applies.
- **The Vaadin Gradle plugin.** `app/` is built by the Vaadin plugin, which runs npm itself (and downloads its own Node.js when none fits). It starts npm inside `app/`, so npm reads `app/.npmrc`. Whether the Node.js it downloads ships an npm that knows `min-release-age` depends on that Node.js version. Where it does not, the lock file (exact versions with integrity hashes) is still what gets installed, but the age rule is not checked. CI does not run the Hilla production build (see [Continuous integration](web-ui.md#continuous-integration)).
- **Transitive npm packages** are covered by `min-release-age` at the time the lock file is written, not when it is installed: `npm ci` installs the locked versions whatever their age.

## Overrides for security fixes

A transitive dependency with a security alert is fixed with an `overrides` entry in `package.json` (exact version) and `npm install --package-lock-only --ignore-scripts`. The target version must be at least 14 days old; if the fix is younger, either wait or record an explicit exception here with the release date and the reason.

Current override: `app/package.json` has `"nanoid@5.0.9": "5.1.16"`. `@vaadin/hilla-react-signals` pins nanoid 5.0.9 exactly; the key replaces only that version. Version 5.1.16 (released 2026-06-24) is the highest fixed version of the three open Dependabot alerts (fixed in 5.1.6, 5.1.11 and 5.1.16). The key is version-qualified because `postcss` uses nanoid 3.x, which is not affected and is CommonJS; a plain `"nanoid"` key would force it to 5.x too. Remove the override when Hilla ships a fixed nanoid itself.

The Vaadin Gradle plugin rewrites the `overrides` block of `app/package.json` (task `vaadinBuildFrontend`). It keeps flat `"name": "version"` entries, including the `name@version` form, and drops nested objects such as `"pkg": { "nanoid": "…" }` (measured 2026-11-19). Use only flat entries there. After any change, run `./gradlew --no-build-cache :app:vaadinBuildFrontend` and check that `git diff app/package.json` still shows the override.
