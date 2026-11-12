# nix-shell でビルド環境を用意する（Android SDK は nix store に入る）
{ pkgs ? import <nixpkgs> {
    config = { allowUnfree = true; android_sdk.accept_license = true; };
  } }:
let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    buildToolsVersions = [ "34.0.0" ];
    includeEmulator = false;
    includeSystemImages = false;
  };
  sdk = androidComposition.androidsdk;
in pkgs.mkShell {
  packages = [ pkgs.jdk17 pkgs.gradle sdk ];
  ANDROID_HOME = "${sdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
  JAVA_HOME = "${pkgs.jdk17}";
  # nix 上の aapt2 を使う（Gradle が取得するバイナリは NixOS で動かないため）
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/libexec/android-sdk/build-tools/34.0.0/aapt2";
}
