{ pkgs, lib, config, ... }:

{
  # The JVM needs no C compiler; TornadoVM ships its native parts prebuilt.
  # Drop these two lines again if a dependency turns out to need cc (JNI, native-image).
  stdenv = pkgs.stdenvNoCC;
  apple.sdk = null;

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

  enterTest = ''
    java --version | grep -q '^openjdk 25'
  '';
}
