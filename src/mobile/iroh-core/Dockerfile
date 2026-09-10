# 在容器内交叉编译 iroh-core 的 Android 原生库（无需本机安装 Rust / NDK）。
# 用法见 build-android-docker.ps1：镜像只负责工具链，源码与产物均通过卷挂载。
FROM rust:1

# NDK 下载地址可覆盖（国内可传入镜像，如 $env:NDK_MIRROR）
ARG NDK_URL=https://dl.google.com/android/repository/android-ndk-r27c-linux.zip

ENV ANDROID_NDK_HOME=/opt/android-ndk \
    ANDROID_API=24

# 安装 Android NDK（r27c）、构建工具与 cargo-ndk，并添加 Android 目标
RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip curl ca-certificates build-essential clang cmake && \
    curl -fsSL --retry 5 --retry-delay 3 --retry-all-errors -o /tmp/ndk.zip "${NDK_URL}" && \
    unzip -q /tmp/ndk.zip -d /opt && \
    mv /opt/android-ndk-r27c /opt/android-ndk && \
    rm /tmp/ndk.zip && \
    cargo install cargo-ndk && \
    rustup target add aarch64-linux-android armv7-linux-androideabi \
        i686-linux-android x86_64-linux-android && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /src
