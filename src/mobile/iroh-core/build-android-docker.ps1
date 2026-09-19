# Cross-compile iroh-core for Android using Docker (no local Rust/NDK needed).
# Prereqs: Docker Desktop with Linux containers; a reachable Docker Hub mirror is recommended.
# Usage (run in this dir):  .\build-android-docker.ps1
# Output: src/mobile/app/src/main/jniLibs/<abi>/libiroh_core.so
#
# NDK fetch (dl.google.com is usually blocked in CN; pick one):
#   1) Drop android-ndk-r27c-linux.zip into this dir; the script serves it via a local HTTP server; or
#   2) Set $env:NDK_MIRROR to any reachable NDK zip URL.

$ErrorActionPreference = "Stop"

$tag = "iroh-core-android-builder"
$dockerfile = Join-Path $PSScriptRoot "Dockerfile"
$src = $PSScriptRoot
$out = Join-Path $PSScriptRoot "..\app\src\main\jniLibs"
$ndkZip = Join-Path $src "android-ndk-r27c-linux.zip"

$buildArgs = @()
$httpJob = $null

if (Test-Path $ndkZip) {
    $port = 8123
    $py = Get-Command python -ErrorAction SilentlyContinue; if (-not $py) { $py = Get-Command python3 -ErrorAction SilentlyContinue }
    if (-not $py) { Write-Error "python/python3 not found; cannot serve local NDK. Use `$env:NDK_MIRROR instead."; exit 1 }
    Write-Host "==> Local NDK zip found; starting local HTTP server (port $port) for the container..."
    $httpJob = Start-Process -FilePath $py.Source -ArgumentList "-m","http.server",$port,"--directory",$src -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 1
    $buildArgs += "--build-arg", "NDK_URL=http://host.docker.internal:$port/android-ndk-r27c-linux.zip"
} elseif ($env:NDK_MIRROR) {
    $buildArgs += "--build-arg", "NDK_URL=$env:NDK_MIRROR"
} else {
    Write-Warning "No local android-ndk-r27c-linux.zip and no `$env:NDK_MIRROR set; will try default dl.google.com (likely fails in CN)."
}

try {
    Write-Host "==> Building image (Rust + NDK + cargo-ndk; first build is slow)..."
    docker build -t $tag -f $dockerfile $src @buildArgs
    if ($LASTEXITCODE -ne 0) { Write-Error "Image build failed"; exit $LASTEXITCODE }

    New-Item -ItemType Directory -Force -Path $out | Out-Null

    Write-Host "==> Compiling and writing .so to $out"
    docker run --rm `
        -v "${src}:/src" `
        -v "${out}:/out" `
        -v iroh-cargo-registry:/usr/local/cargo/registry `
        -v iroh-cargo-git:/usr/local/cargo/git `
        $tag `
        cargo ndk --platform 24 `
            -t arm64-v8a -t armeabi-v7a -t x86 -t x86_64 `
            -o /out build --release

    if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed"; exit $LASTEXITCODE }

    # 只保留 libiroh_core.so：libiroh-*.so / libiroh_relay-*.so 是同一次 cargo 构建的副产物
    # （iroh / iroh-relay 的 cdylib），core 并未引用它们，删掉可减小 APK
    Get-ChildItem -Path $out -Recurse -File |
        Where-Object { $_.Name -like "libiroh-*.so" -or $_.Name -like "libiroh_relay-*.so" } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

    Write-Host ""
    Write-Host "==> Artifacts:"
    Get-ChildItem -Recurse $out -Filter "libiroh_core.so" | ForEach-Object { Write-Host "   $($_.FullName)" }
    Write-Host ""
    Write-Host "Next: 启动桌面端（pnpm dev）后用 App 扫码连接。"
} finally {
    if ($httpJob) { Stop-Process -Id $httpJob.Id -Force -ErrorAction SilentlyContinue }
}
