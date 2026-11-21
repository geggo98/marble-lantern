{ pkgs, lib, config, ... }:

{
  # The JVM needs no C compiler; TornadoVM ships its native parts prebuilt.
  # Drop these two lines again if a dependency turns out to need cc (JNI, native-image).
  stdenv = pkgs.stdenvNoCC;
  apple.sdk = null;

  # C++ port (cpp/): CMake, Ninja and clang-tidy from nixpkgs; the compiler stays Apple clang (stdenvNoCC, so c++ is /usr/bin/c++).
  packages = [
    pkgs.cmake pkgs.ninja pkgs.llvmPackages.clang-tools
    pkgs.emscripten pkgs.zstd pkgs.zsh pkgs.librsvg pkgs.uv
    pkgs.pinact pkgs.actionlint pkgs.zizmor pkgs.gitleaks
  ];

  languages.javascript = {
    enable = true;
    package = pkgs.nodejs_24;
    npm.enable = true;
  };

  # The Nix store is read-only; emscripten needs a writable cache.
  env.EM_CACHE = "${config.devenv.state}/em_cache";
  env.PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers}";
  env.PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS = "true";

  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk25;
  };

  # Secrets and local settings live in .env (gitignored); .env.example documents the keys.
  dotenv.enable = true;

  # TornadoVM is not in nixpkgs: scripts/setup-tornado.sh fetches the pinned release
  # (SHA-256 checked) into tornado-sdk/. Run once: devenv tasks run setup:tornado
  env.TORNADOVM_HOME = "${config.devenv.root}/tornado-sdk/current";
  tasks."setup:tornado" = { exec = "${config.devenv.root}/scripts/setup-tornado.sh"; };

  enterShell = ''
    export PATH="$TORNADOVM_HOME/bin:$PATH"
    [ -x "$TORNADOVM_HOME/bin/tornado" ] || echo "TornadoVM SDK missing: run 'devenv tasks run setup:tornado'" >&2
  '';

  # Gradle must never serve stale results while the build is being changed.
  # The Gradle wrapper pins Gradle 9.8.2, which supports JDK 25; the nixpkgs Gradle (8.14) does not.
  tasks."agent:build" = { exec = "${config.devenv.root}/gradlew --no-build-cache build -x test"; };
  tasks."agent:test" = { exec = "${config.devenv.root}/gradlew --no-build-cache test"; };

  # Writes the JSON data files of the About figures (web/src/about/data/) from the real engine and the shipped
  # models; without this task, ExplainerDataTest only verifies the committed files (plan 6.3).
  tasks."about:data" = { exec = "${config.devenv.root}/gradlew --no-build-cache :search:test --tests sternhalma.search.ExplainerDataTest -Pexplainer.write=true --rerun"; };

  # Web UI tasks (plan section 4). Each script is a stub until its work package replaces it.
  tasks."tables:pack-rows1" = { exec = "${config.devenv.root}/scripts/tables/pack-rows1.zsh"; };
  tasks."tables:unpack" = { exec = "${config.devenv.root}/scripts/tables/unpack-rows1.zsh"; };
  tasks."web:tables" = { exec = "${config.devenv.root}/scripts/web/tables.zsh"; };
  tasks."web:models" = { exec = "${config.devenv.root}/scripts/web/models.zsh"; };
  tasks."wasm:build" = { exec = "${config.devenv.root}/scripts/web/build-wasm.zsh"; };
  tasks."wasm:test" = { exec = "${config.devenv.root}/scripts/web/test-wasm.zsh"; after = [ "wasm:build" "web:tables" ]; };
  tasks."teavm:build" = { exec = "${config.devenv.root}/scripts/web/build-teavm.zsh"; };
  tasks."teavm:build-net" = { exec = "${config.devenv.root}/scripts/web/build-teavm-net.zsh"; };
  tasks."web:engines" = { exec = "${config.devenv.root}/scripts/web/engines-manifest.zsh"; after = [ "wasm:build" "teavm:build" "teavm:build-net" ]; };
  tasks."web:install" = { exec = "${config.devenv.root}/scripts/web/install.zsh"; };
  tasks."web:test" = { exec = "${config.devenv.root}/scripts/web/test.zsh"; after = [ "web:install" ]; };
  tasks."web:build" = { exec = "${config.devenv.root}/scripts/web/build.zsh"; after = [ "web:engines" "web:tables" "web:models" "web:install" ]; };
  tasks."web:bench" = { exec = "${config.devenv.root}/scripts/web/bench.zsh"; after = [ "web:engines" "web:tables" ]; };
  tasks."web:e2e" = { exec = "${config.devenv.root}/scripts/web/e2e.zsh"; after = [ "web:build" ]; };
  tasks."web:ci" = { exec = "${config.devenv.root}/scripts/web/ci.zsh"; after = [ "wasm:build" "wasm:test" "teavm:build" "teavm:build-net" "web:engines" "web:install" "web:test" "web:tables" "web:build" "web:bench" "web:e2e" ]; };
  tasks."app:run" = { exec = "${config.devenv.root}/scripts/web/app-run.zsh"; };
  tasks."app:run-tornado" = { exec = "${config.devenv.root}/scripts/web/app-run-tornado.zsh"; };
  tasks."ci:security" = { exec = "${config.devenv.root}/scripts/ci/security.zsh"; };

  enterTest = ''
    java --version | grep -q '^openjdk 25'
  '';
}
