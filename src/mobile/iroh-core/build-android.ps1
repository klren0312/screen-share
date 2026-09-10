# 构建 iroh-core 的 Android 原生库（JNI），产物为各 ABI 的 libircore.so。
#
# 前置条件：
#   1. 安装 Rust（rustup）并确认可用：  rustc --version
#   2. 安装 cargo-ndk：                cargo install cargo-ndk
#   3. 安装交叉编译目标：
#        rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
#   4. 安装 Android NDK，并导出环境变量：
#        $env:ANDROID_NDK_HOME = "C:\path\to\android-ndk"   # 或 $env:NDK_HOME
#
# 用法（在本目录执行）：  .\build-android.ps1
# 产物输出到：src/mobile/app/src/main/jniLibs/<abi>/libiroh_core.so

$ErrorActionPreference = "Stop"

if (-not $env:ANDROID_NDK_HOME -and -not $env:NDK_HOME) {
    Write-Error "请先设置 ANDROID_NDK_HOME（或 NDK_HOME）指向 Android NDK 目录"
    exit 1
}

# 确认 cargo-ndk 可用
if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 cargo-ndk，请先执行：cargo install cargo-ndk"
    exit 1
}

$out = Join-Path $PSScriptRoot "..\app\src\main\jniLibs"
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "==> 构建 iroh-core（release，4 个 ABI）..."
cargo ndk `
    -t arm64-v8a `
    -t armeabi-v7a `
    -t x86 `
    -t x86_64 `
    -o $out `
    build --release

if ($LASTEXITCODE -ne 0) {
    Write-Error "构建失败"
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "==> 产物已输出到: $out"
Get-ChildItem -Recurse $out -Filter "libiroh_core.so" | ForEach-Object { Write-Host "   $($_.FullName)" }
Write-Host ""
Write-Host "下一步：在 ScreenCaptureService.irohTicket 填入信令网桥启动时的 ticket，"
Write-Host "        并将 app 的 build.gradle 配置 jniLibs 目录（见 iroh-core/README.md）。"
