{ pkgs, lib, config, ... }:

{
  # The JVM needs no C compiler; TornadoVM ships its native parts prebuilt.
  # Drop these two lines again if a dependency turns out to need cc (JNI, native-image).
  stdenv = pkgs.stdenvNoCC;
  apple.sdk = null;

  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk25;
    gradle.enable = true;
  };

  # Secrets and local settings live in .env (gitignored); .env.example documents the keys.
  dotenv.enable = true;

  # Gradle must never serve stale results while the build is being changed.
  tasks."agent:build" = { exec = "gradle --no-build-cache build -x test"; };
  tasks."agent:test" = { exec = "gradle --no-build-cache test"; };

  enterTest = ''
    java --version | grep -q '^openjdk 25'
  '';
}
