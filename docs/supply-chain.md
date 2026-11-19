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

- **Gradle dependencies.** There is no cooldown. The checksums in `gradle/verification-metadata.xml` protect integrity only: a changed artifact fails the build, but a freshly published version that is entered into the file is accepted. See [Gradle: verification metadata, mirror, retries and cache](#gradle-verification-metadata-mirror-retries-and-cache).
- **devenv and Nix inputs.** `devenv.lock` pins them. They change only on a manual `devenv update`; no age check applies.
- **The Vaadin Gradle plugin.** `app/` is built by the Vaadin plugin, which runs npm itself (and downloads its own Node.js when none fits). It starts npm inside `app/`, so npm reads `app/.npmrc`. Whether the Node.js it downloads ships an npm that knows `min-release-age` depends on that Node.js version. Where it does not, the lock file (exact versions with integrity hashes) is still what gets installed, but the age rule is not checked. CI does not run the Hilla production build (see [Continuous integration](web-ui.md#continuous-integration)).
- **Transitive npm packages** are covered by `min-release-age` at the time the lock file is written, not when it is installed: `npm ci` installs the locked versions whatever their age.

## Overrides for security fixes

A transitive dependency with a security alert is fixed with an `overrides` entry in `package.json` (exact version) and `npm install --package-lock-only --ignore-scripts`. The target version must be at least 14 days old; if the fix is younger, either wait or record an explicit exception here with the release date and the reason.

Current override: `app/package.json` has `"nanoid@5.0.9": "5.1.16"`. `@vaadin/hilla-react-signals` pins nanoid 5.0.9 exactly; the key replaces only that version. Version 5.1.16 (released 2026-06-24) is the highest fixed version of the three open Dependabot alerts (fixed in 5.1.6, 5.1.11 and 5.1.16). The key is version-qualified because `postcss` uses nanoid 3.x, which is not affected and is CommonJS; a plain `"nanoid"` key would force it to 5.x too. Remove the override when Hilla ships a fixed nanoid itself.

The Vaadin Gradle plugin rewrites the `overrides` block of `app/package.json` (task `vaadinBuildFrontend`). It keeps flat `"name": "version"` entries, including the `name@version` form, and drops nested objects such as `"pkg": { "nanoid": "…" }` (measured 2026-11-19). Use only flat entries there. After any change, run `./gradlew --no-build-cache :app:vaadinBuildFrontend` and check that `git diff app/package.json` still shows the override.

## Gradle: verification metadata, mirror, retries and cache

TL;DR: every Gradle dependency of the root build and of `build-logic` is checked against a SHA-256 checksum in `gradle/verification-metadata.xml`, whichever repository served it. In CI, Gradle resolves from a public mirror of Maven Central first whenever a probe shows that the mirror is healthy, and from Maven Central only as a fallback; a full build then sent 0 requests to Maven Central. If the mirror fails in the middle of a build, a wrapper script reruns the command once against Maven Central. Downloads throttled with HTTP 429 are tried up to 8 times (7 retries), and CI caches the downloaded dependencies between runs. All facts in this section were measured or checked on 2026-11-19 unless marked otherwise.

Terms:

- **Dependency verification:** Gradle compares the checksum of every downloaded artifact (jar, pom, module file) with the value in `gradle/verification-metadata.xml` and fails the build on a mismatch or on an artifact the file does not list.
- **Included build:** a separate Gradle build that the root build pulls in with `includeBuild`. Here that is `build-logic/`, which holds the shared build conventions (convention plugins).
- **Buildscript classpath:** the dependencies a build needs to run its own build scripts and plugins, as opposed to the dependencies of the code it compiles. For `build-logic` that is, for example, the `kotlin-dsl` plugin and `kotlin-stdlib` 2.4.10.
- **HTTP 429:** "Too Many Requests", the status a server returns when it throttles a client.
- **Gradle plugin portal:** `plugins.gradle.org`, the repository from which Gradle resolves plugins by default.
- **Plugin marker:** a small module named `<plugin id>.gradle.plugin` that maps a plugin id (as written in a `plugins { id(...) }` block) to the module that contains the plugin.
- **Init script:** a Gradle script in `$GRADLE_USER_HOME/init.d/` that Gradle runs before every build on that machine. It can change repositories without a change to the build files.

### Dependency verification

| Item | Value |
|---|---|
| File | `gradle/verification-metadata.xml` |
| Components listed | 559 (counted with `grep -c '<component '`) |
| Checks | SHA-256 only: `<verify-metadata>true</verify-metadata>`, `<verify-signatures>false</verify-signatures>` |
| Scope | the root build and the included build `build-logic`, including its buildscript classpath |
| Regenerate with | `scripts/gradle-verification-update.zsh` |

**One file covers both builds.** Gradle uses the verification file of the current (outer) build and ignores the own file of an included build ([Gradle docs, "Scope of Dependency Verification"](https://docs.gradle.org/current/userguide/securing_dependencies.html)). A control test confirmed this: a `build-logic/gradle/verification-metadata.xml` with an all-zero checksum did not fail the build. For that reason `build-logic` has no file of its own.

**Regeneration.** `scripts/gradle-verification-update.zsh` runs `./gradlew --write-verification-metadata sha256 build resolveAllDeps` with a fresh, empty `GRADLE_USER_HOME`, so every artifact comes from its real origin (Maven Central and the Gradle plugin portal), not from a local cache. `resolveAllDeps` is a task that the script adds through a temporary init script; it resolves every resolvable configuration of every project, buildscript configurations included, so that configurations a plain `build` never touches are recorded as well. A run on 2026-11-19 took about 7.5 minutes and produced a byte-identical file (0 entries added, 0 removed).

**Proof that the file is complete and enforced** (measured 2026-11-19):

| Test | Result |
|---|---|
| `./gradlew --no-build-cache --dependency-verification strict build` with another fresh `GRADLE_USER_HOME` | passed, about 3 minutes |
| One checksum changed: the `junit-jupiter-api` 6.1.3 jar (root build) | build failed and named the artifact |
| One checksum changed: the `kotlin-stdlib` 2.4.10 jar (buildscript classpath of `build-logic`) | build failed and named the artifact |

Both changed checksums were restored afterwards.

**Gradle wrapper.** `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` matches the published checksum of `gradle-9.8.2-bin.zip` on [gradle.org/release-checksums](https://gradle.org/release-checksums/) (checked 2026-11-19).

**Not covered by dependency verification:**

- the jars of the local TornadoVM SDK (see [tornado.md](tornado.md)): they are files on disk, not downloads from a repository, and the SDK is absent in CI. (The `tornado-api` artifacts from Maven Central are listed.)
- SNAPSHOT versions and artifacts built locally;
- the Hilla production frontend (the React frontend of `app/`, see [web-ui.md](web-ui.md)): npm packages and the Node.js that the Vaadin plugin downloads (see the bullet "The Vaadin Gradle plugin" under [What the policy does not cover](#what-the-policy-does-not-cover)).

**Consequences for updates.** A Dependabot bump of a Gradle dependency, or any new dependency, fails the build until someone runs `scripts/gradle-verification-update.zsh` and commits the diff. A version that is entered into the file is accepted whatever its age, so there is still no cooldown for Gradle.

**What verification does not fix.** On 2026-11-19 a CI run failed twice with `Could not resolve org.jetbrains.kotlin:kotlin-stdlib:2.4.10` and `429 Too Many Requests` from `repo.maven.apache.org`. That was not a gap in the metadata: the file was complete. Verification checks integrity; it does not prevent throttling. The mirror, the retry settings and the cache below address the throttling.

### Maven Central mirror in CI

Maven Central is run by a non-profit organisation, and GitHub-hosted runners share IP addresses, so Maven Central throttles them with HTTP 429. The project owner decided on 2026-11-19 to spare Maven Central: CI resolves from a mirror first whenever the mirror serves the expected checksums, and from Maven Central only as a fallback. Developer machines are not affected; the mirror is set up only in CI.

**The mirror.** `https://maven-central.storage-download.googleapis.com/maven2`, a copy of Maven Central in Google Cloud Storage, US region.

| Property | Value |
|---|---|
| Content | byte-identical to Maven Central for the checked artifacts: their SHA-256 equals the value in `gradle/verification-metadata.xml` |
| Support | Google's own index page for it ([maven-central.storage.googleapis.com/index.html](https://maven-central.storage.googleapis.com/index.html), read 2026-11-19) says that it is not an officially supported Google product |
| Terms, rate limit, availability | none documented |
| Freshness | lags Maven Central by hours; EU and Asia variants exist, the US one syncs most often (about 12 times a day against about 4 for EU and Asia, per a [Travis CI community thread](https://travis-ci.community/t/maven-builds-that-use-the-gcs-maven-central-mirror-should-update-their-paths/5926); not confirmed by Google) |
| Alternatives | no mirror documented by GitHub or Azure for hosted runners was found |

**Why plugin resolution matters as well.** The Gradle plugin portal answers requests for artifacts that also exist on Maven Central with an HTTP redirect to `repo.maven.apache.org` (Maven Central). So every library that a plugin needs reaches Maven Central through the portal, even when no build file names Maven Central. On 2026-11-19 a CI run failed after 14 s while it resolved the buildscript classpath of `build-logic`: `Could not find org.jetbrains.kotlin:kotlin-stdlib:2.4.10 ... Searched in: plugins.gradle.org/m2/...`. A mirror only for ordinary dependencies would not have helped there.

#### How it works

1. **Probe.** Before any Gradle call, a step "Prefer the Gradle mirror when it is healthy" runs `scripts/ci/select-gradle-repos.zsh`. That step exists in two of the four jobs of `.github/workflows/ci.yml` (the Java job and the Web job; the other two run no Gradle) and in `.github/workflows/pages.yml`. The script picks two small `.pom` or `.module` files from `gradle/verification-metadata.xml` (those of `kotlin-stdlib` and `junit-bom`, otherwise the first entries) and downloads them from the mirror with a 10 s timeout and one retry. The mirror is healthy when both answer HTTP 200 and their SHA-256 equals the value in the metadata.
2. **Healthy:** the script installs the init script `scripts/ci/gradle-mirror.init.gradle` as `$GRADLE_USER_HOME/init.d/ci-mirror.init.gradle` and prints a `::notice::` annotation. **Unhealthy:** it installs nothing, removes a leftover from an earlier run, and prints a `::warning::` annotation with the reason; the build then resolves from Maven Central.
3. **Init script.** It runs for the settings of every build in the composite, that is the root build and the included build `build-logic`. It puts the mirror first in two lists:
   - dependency repositories: the mirror before `mavenCentral()`;
   - plugin repositories: the mirror before the plugin portal, except for plugin markers (module name matching `.*\.gradle\.plugin`) and for the Vaadin Gradle plugins `com.vaadin:vaadin-gradle-plugin`, `com.vaadin:flow-gradle-plugin` and `com.vaadin:hilla-gradle-plugin`. Their bytes differ between the plugin portal and Maven Central (and therefore the mirror), and `gradle/verification-metadata.xml` records the portal copy. Gradle skips the mirror for them, so the portal keeps serving them.

   What the mirror lacks (a release from the last hours, a plugin that exists only on the portal) it answers with 404, and Gradle then asks the next repository.
4. **Fallback wrapper.** Every CI command that runs Gradle runs through `scripts/ci/run-with-mirror-fallback.zsh` (see [Limits of Gradle's repository fallthrough](#limits-of-gradles-repository-fallthrough) for why).

Safeguards in `select-gradle-repos.zsh`: without `--gradle-home` or `GRADLE_USER_HOME` it would write into `~/.gradle`, and it refuses to do so outside GitHub Actions (exit 2), so a developer cannot change their own Gradle setup by accident. It accepts only `https://` URLs made of letters, digits and `._~:/-`, because the URL is written into a Groovy string. The option `--allow-insecure-http` also accepts `http://`; it exists only for a test with a local server and is not used in CI. `--mirror URL` or the environment variable `CI_MAVEN_MIRROR` selects another mirror.

**Integrity does not depend on the mirror.** Dependency verification stays strict (strict is Gradle's default mode, so the CI command `./gradlew --no-build-cache build` is strict without the `--dependency-verification` option) and checks every artifact against `gradle/verification-metadata.xml`, whichever repository served it. A mirror that serves other bytes fails the build; it cannot place code in the build. The row "mirror serves a changed jar" in the test table under [Limits of Gradle's repository fallthrough](#limits-of-gradles-repository-fallthrough) confirms this.

#### Measured effect

Requests per host, counted in the `--debug` log with a fresh `GRADLE_USER_HOME` and strict verification (2026-11-19):

| Build | Mirror | `plugins.gradle.org` | `repo.maven.apache.org` (Maven Central) |
|---|---|---|---|
| Throwaway test project (not in the repository) that applies only plugins (Spring Boot and Vaadin), without mirror | – | 314 | 298 (redirect targets) |
| Same project, with mirror | 336 | 10 | 0 |
| `./gradlew --no-build-cache --dependency-verification strict build` including `:app`, with mirror | 1036 | 17 (plus 9 redirects to `plugins-artifacts.gradle.org`, the host from which the portal serves plugin files) | 0 |

The full build with mirror ended `BUILD SUCCESSFUL` after 7 min 23 s. An earlier version of the init script, which kept the plugin portal first for plugins (commit `ed496b7`), still sent 391 redirects to Maven Central over 38 TLS connections.

#### Limits of Gradle's repository fallthrough

Gradle asks the next repository in the list only when the current one answers 404. Measured with local repository servers on 2026-11-19:

| First repository answers | Result |
|---|---|
| 404 | Gradle asks the next repository |
| 429, 500 or 503 | the build fails; the next repository is never asked |
| the pom, but 404 for the jar | the build fails: `Could not find ...jar` |
| an artifact whose checksum does not match | the build fails; a verification failure never falls through |

So a mirror that fails after a healthy probe would fail the build, although Maven Central could have answered. `scripts/ci/run-with-mirror-fallback.zsh` handles that case. It runs the command and keeps a copy of its output. It reruns the command once, after it deletes the init script and prints a `::warning::`, only if all of these hold:

- the command failed;
- the init script is installed;
- the output names the mirror (its host or the repository name `ciMirror`) and shows a transport or lookup failure: `Received status code` 4xx or 5xx, `Could not find`, `Could not GET`, `Could not HEAD`, or `timed out`;
- the output does not contain `Dependency verification failed`.

A test or compile failure and a tampered artifact are never rerun. The exit code is that of the last run. The retry settings from `gradle.properties` apply to both runs.

Tests (2026-11-19):

| Test | Result |
|---|---|
| `scripts/ci/test/run-with-mirror-fallback.test.zsh`: a stub command instead of Gradle, no network; also runs in the Java job of `ci.yml` | 11 of 11 cases pass |
| Real Gradle, mirror answers 429 after 6 requests | rerun against Maven Central, build passes |
| Real Gradle, mirror answers 500 | rerun, build passes |
| Real Gradle, mirror has the pom but answers 404 for the jar | rerun, build passes |
| Real Gradle, mirror serves a changed jar with a matching `.sha1` file | `Dependency verification failed`, exit 1, no rerun, no request to Maven Central |
| Real Gradle, a task fails for a reason unrelated to the mirror | no rerun |
| Real Gradle, healthy mirror | one run, 0 requests to Maven Central |

#### Risks and open points

- **The fault-injection harness is not in the repository.** The real-Gradle tests above used a harness: local HTTP servers that act as the mirror and answer with chosen faults (429, 500, a missing jar, changed bytes), and a fresh `GRADLE_USER_HOME` per test; the option `--allow-insecure-http` of `select-gradle-repos.zsh` exists for such a local server. The harness lived in a temporary directory and is gone. Only the stub test is kept. Rebuild a harness before you rely on the real-Gradle results again.
- **The exclusion list is measured by hand.** After an update of the Vaadin or Spring Boot plugins, another plugin may differ in bytes between the portal and the mirror. Symptom: a strict verification failure that names the plugin. It is loud and never rerun. Fix: add the module to the exclusions in `scripts/ci/gradle-mirror.init.gradle`, after a check with a fault-injection harness or a `--debug` log.
- **A rerun of `web:ci` repeats all of it.** `web:ci` is a devenv task (defined in `devenv.nix`) that runs `scripts/web/ci.zsh`: engine tests, build, web tests and browser tests, with Gradle inside. The workflows run it as `devenv tasks run --mode single web:ci` (`--mode single` runs only that task, not the tasks it depends on), and the wrapper treats that as one command, so a mirror failure late in that task reruns every step of it, including steps that had passed.
- **First run on GitHub.** The mirror steps and the wrapper calls in `ci.yml` and `pages.yml` (commits `b5d1cf2` to `e61fff1`, 2026-11-19) were first tested locally. The first GitHub Actions run with them (CI run 44514760124, 2026-11-19) passed all jobs; in the Java job the probe reported "mirror healthy" and the init script set the order `[ciMirror, MavenRepo]` for dependencies. Request counts per host on GitHub were not measured; the numbers above are from local runs.
- **The mirror makes no promises.** If it disappears or lags, the probe fails and CI falls back to Maven Central with a warning; it does not break.

**Possible next step** (not done, a decision for the project owner): a repository manager with its own cache, such as a self-hosted or hosted Nexus or Artifactory, as the only repository. It would fetch each artifact from Maven Central once and then serve it from its cache.

### Download retries

Gradle's default is 3 attempts with a 125 ms initial backoff, and it retries on HTTP 429. `gradle.properties` raises both:

```properties
systemProp.org.gradle.internal.repository.max.tentatives=8
systemProp.org.gradle.internal.repository.initial.backoff=1000
```

The backoff doubles after each attempt, so the worst case is 1 + 2 + 4 + … + 64 = 127 seconds of waiting per file. The property names are internal and not documented; they were found with `strings` in `gradle-dependency-management-9.8.2.jar`. The name `max.retries` does not exist. The `systemProp.` prefix is required, because Gradle reads these values as JVM system properties.

Fault injection with a local HTTP server that answers 429 a set number of times and then 200 (measured 2026-11-19):

| Server behaviour | Properties set | Result |
|---|---|---|
| 2 × 429, then 200 | no | resolved (3 requests) |
| 5 × 429, then 200 | no | failed: `Received status code 429` |
| 5 × 429, then 200 | yes | resolved (6 requests) |
| 5 × 429, then 200, for a buildscript classpath dependency of an included build | yes | resolved; the root `gradle.properties` reaches included builds |

Not tested: the resolution of plugin marker artifacts from the Gradle plugin portal itself.

Revisit: the property names are internal, so check them again after each Gradle upgrade (run `strings` over the new `gradle-dependency-management-*.jar`, found under `~/.gradle/wrapper/dists/gradle-<version>-bin/<hash>/gradle-<version>/lib/plugins/`).

### Dependency cache in CI

Both workflows, `.github/workflows/ci.yml` and `.github/workflows/pages.yml`, restore and save the Gradle downloads with `actions/cache` (pinned by commit SHA, v6.1.0).

| Item | Value |
|---|---|
| Cached paths | `~/.gradle/caches/modules-2` (downloaded dependencies), `~/.gradle/wrapper/dists` (Gradle distribution) |
| Key | `gradle-deps-<os>-<hash>`; the hash covers `gradle/verification-metadata.xml`, `gradle/libs.versions.toml`, `**/*.gradle.kts` and `gradle/wrapper/gradle-wrapper.properties` |
| Restore key | prefix `gradle-deps-<os>-`, so a changed key still starts from the newest older cache |
| Not cached | build outputs, the configuration cache, secrets |

Security: GitHub scopes caches to the repository, and a pull request writes only to the scope of its own ref, so it cannot change the cache that `main` reads. An artifact restored from the cache is checked by dependency verification exactly like a downloaded one.

Not yet verified:

- whether `modules-2` alone is enough. Derived caches such as `~/.gradle/caches/jars-9` are left out on purpose.
- the effect on HTTP 429. A warm cache avoids most downloads. A cold run (the first run, or the first run after a dependency change) still downloads everything, from the mirror when it is healthy, otherwise from Maven Central.

`ci.yml` and `pages.yml` with the cache steps passed the workflow checks of `scripts/ci/security.zsh` locally: pinact (actions pinned by SHA), actionlint (workflow syntax), zizmor (workflow security) and gitleaks (secrets).
