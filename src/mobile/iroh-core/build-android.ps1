# Build iroh-core Android native libs (JNI). Output: per-ABI libircore.so.
#
# Prereqs:
#   1. Rust (rustup)
#   2. cargo-ndk:            cargo install cargo-ndk
#   3. Android targets:      rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
#   4. Android NDK, export:  $env:ANDROID_NDK_HOME = "C:\path\to\android-ndk"
#
# Usage (in this dir):  .\build-android.ps1
# Output: src/mobile/app/src/main/jniLibs/<abi>/libiroh_core.so

$ErrorActionPreference = "Stop"

if (-not $env:ANDROID_NDK_HOME -and -not $env:NDK_HOME) {
    Write-Error "Please set ANDROID_NDK_HOME (or NDK_HOME) to your Android NDK directory"
    exit 1
}

if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    Write-Error "cargo-ndk not found; run: cargo install cargo-ndk"
    exit 1
}

$out = Join-Path $PSScriptRoot "..\app\src\main\jniLibs"
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "==> Building iroh-core (release, 4 ABIs)..."
cargo ndk --platform 24 `
    -t arm64-v8a `
    -t armeabi-v7a `
    -t x86 `
    -t x86_64 `
    -o $out `
    build --release

if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed"
    exit $LASTEXITCODE
}

# 只保留 libiroh_core.so：libiroh-*.so / libiroh_relay-*.so 是同一次 cargo 构建的副产物
# （iroh / iroh-relay 的 cdylib），core 并未引用它们（NEEDED 仅 libc/libm/libdl），删掉可减小 APK
Get-ChildItem -Path $out -Recurse -File |
    Where-Object { $_.Name -like "libiroh-*.so" -or $_.Name -like "libiroh_relay-*.so" } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

Write-Host ""
Write-Host "==> Output: $out"
Get-ChildItem -Recurse $out -Filter "libiroh_core.so" | ForEach-Object { Write-Host "   $($_.FullName)" }
Write-Host ""
Write-Host "Next: 启动桌面端（pnpm dev）后用 App 扫码连接，详见 iroh-core/README.md。"
