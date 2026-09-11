package com.screenshare.webrtc

import android.content.Context
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

// 单例持有 PeerConnectionFactory（全局只初始化一次）
object PeerConnectionFactoryHolder {
    private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun factory(context: Context): PeerConnectionFactory {
        if (factory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .createInitializationOptions(),
            )
            val eglBase =
                EglBase.create()
                    ?: throw RuntimeException("EGL initialization failed: EglBase.create() returned null")
            factory =
                PeerConnectionFactory
                    .builder()
                    .setVideoEncoderFactory(
                        org.webrtc.DefaultVideoEncoderFactory(
                            eglBase.eglBaseContext,
                            true,
                            true,
                        ),
                    ).setVideoDecoderFactory(
                        org.webrtc.DefaultVideoDecoderFactory(
                            eglBase.eglBaseContext,
                        ),
                    ).createPeerConnectionFactory()
        }
        return factory!!
    }
}
