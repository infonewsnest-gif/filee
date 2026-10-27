package com.streamcast.studio.stream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.generic.GenericStream
import com.pedro.library.view.OpenGlView

/**
 * RootEncoder দিয়ে ফোনের ক্যামেরা + মাইক থেকে RTMP/RTMPS লাইভ পাঠায়।
 * স্ট্রিমিং সংক্রান্ত সব কোড এই একটা ফাইলে রাখা হয়েছে।
 */
class LiveStreamer(
    private val context: Context,
    private val listener: Listener
) : ConnectChecker {

    interface Listener {
        fun onStatus(message: String)
        fun onStopped()
    }

    private val main = Handler(Looper.getMainLooper())
    private val stream = GenericStream(context, this)
    private var prepared = false
    private var previewView: OpenGlView? = null

    private fun hasPermissions(): Boolean =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun prepare(): Boolean {
        if (prepared) return true
        // 1280x720 + rotation 90 = পোর্ট্রেট (720x1280) আউটপুট
        val video = stream.prepareVideo(1280, 720, 2_500_000, 30, 2, 90)
        val audio = stream.prepareAudio(44100, true, 128_000)
        prepared = video && audio
        return prepared
    }

    fun attachPreview(view: OpenGlView) {
        previewView = view
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                refreshPreview()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (stream.isOnPreview) stream.stopPreview()
            }
        })
    }

    /** পারমিশন পাওয়ার পরে বা অ্যাপে ফিরে এলে ক্যামেরা প্রিভিউ চালু করে */
    fun refreshPreview() {
        val view = previewView ?: return
        if (!hasPermissions() || !view.holder.surface.isValid) return
        if (prepare() && !stream.isOnPreview) stream.startPreview(view)
    }

    fun start(url: String): Boolean {
        if (!hasPermissions() || !prepare()) return false
        if (!stream.isStreaming) stream.startStream(url)
        return true
    }

    fun stop() {
        if (stream.isStreaming) stream.stopStream()
    }

    fun release() {
        try {
            if (stream.isStreaming) stream.stopStream()
            if (stream.isOnPreview) stream.stopPreview()
            stream.release()
        } catch (_: Exception) {
        }
    }

    // ---- ConnectChecker (এই কলগুলো ব্যাকগ্রাউন্ড থ্রেড থেকে আসে) ----

    override fun onConnectionStarted(url: String) {
        main.post { listener.onStatus("সংযোগ হচ্ছে...") }
    }

    override fun onConnectionSuccess() {
        main.post { listener.onStatus("🔴 লাইভ চলছে") }
    }

    override fun onConnectionFailed(reason: String) {
        main.post {
            stop()
            listener.onStatus("সংযোগ ব্যর্থ: $reason")
            listener.onStopped()
        }
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        main.post {
            listener.onStatus("লাইভ বন্ধ হয়েছে")
            listener.onStopped()
        }
    }

    override fun onAuthError() {
        main.post {
            stop()
            listener.onStatus("অথ ত্রুটি: Stream Key ঠিক আছে কি দেখুন")
            listener.onStopped()
        }
    }

    override fun onAuthSuccess() {}
}
