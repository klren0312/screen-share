# 当前 minifyEnabled=false（见 build.gradle），此处仅保留 JNI 回调相关的占位规则。

# iroh 原生核心通过 JNI 回调 Kotlin 静态方法 IrohCore.onMessage，
# 一旦开启混淆必须保留该类及其方法名。
-keep class com.screenshare.webrtc.IrohCore { *; }
