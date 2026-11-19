#!/bin/zsh
# Regenerates gradle/verification-metadata.xml (sha256 only, no signatures).
#
# Why a fresh GRADLE_USER_HOME: a warm home serves artifacts from its cache and Gradle then
# records the checksum of the cached file. A fresh home forces a download from the real origin
# (Maven Central, Gradle plugin portal), so the recorded checksum is the first-use checksum of
# the origin, not of whatever an older run or a mirror left behind.
#
# Why no per-build file for build-logic: Gradle verifies an included build with the file of the
# CURRENT (outer) build and ignores the included build's own file
# (https://docs.gradle.org/current/userguide/securing_dependencies.html, "Scope of Dependency
# Verification"). The root file therefore covers build-logic as well.
#
# Why the init script: --write-verification-metadata records only what a run resolves. The init
# script adds a task that resolves every resolvable configuration of every project (and the
# buildscript configurations), so that configurations a plain `build` never touches are recorded.
#
# Usage: scripts/gradle-verification-update.zsh   (from anywhere; needs network access)
set -eu

root=${0:A:h:h}
cd "$root"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/resolve-all.init.gradle" <<'EOF'
allprojects {
    tasks.register("resolveAllDeps") {
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doLast {
            def all = []
            all.addAll(project.configurations.toList())
            all.addAll(project.buildscript.configurations.toList())
            all.findAll { it.canBeResolved }.each { c ->
                try {
                    c.incoming.artifactView { lenient = true }.files.files
                } catch (Exception e) {
                    println "UNRESOLVED ${project.path}:${c.name}: ${e.message?.readLines()?.first()}"
                }
            }
        }
    }
}
EOF

# Fresh home for the Gradle run; --no-build-cache per the project rule.
export GRADLE_USER_HOME="$tmp/gradle-home"
mkdir -p "$GRADLE_USER_HOME"

./gradlew --no-build-cache --continue \
  --write-verification-metadata sha256 \
  -I "$tmp/resolve-all.init.gradle" \
  build resolveAllDeps

# Gradle may rewrite the configuration block; the project policy is checksums only, so fail loudly.
grep -q "<verify-signatures>false</verify-signatures>" gradle/verification-metadata.xml || { print -u2 "config block changed: verify-signatures is not false"; exit 1 }
grep -q "<verify-metadata>true</verify-metadata>" gradle/verification-metadata.xml || { print -u2 "config block changed: verify-metadata is not true"; exit 1 }

git diff --stat -- gradle/verification-metadata.xml
