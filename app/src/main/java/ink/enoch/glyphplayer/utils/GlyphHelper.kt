package ink.enoch.glyphplayer.utils

import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.os.Looper
import java.lang.ref.WeakReference
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager

/** Phone (3a)/(3a) Pro share the 24111 layout: A=11, B=5, C=20 LEDs. */
object GlyphHelper {
    data class GlyphTick(
        var lightA: Float, var lightB: Float, var lightC: Float,
        val reverseA: Boolean, val reverseB: Boolean, val reverseC: Boolean
    )

    private val defaultOwner = Any()
    private var currentOwner: Any = defaultOwner
    private var onReady: (() -> Unit)? = null
    private var manager: GlyphManager? = null
    private var ready = false
    private var generation = 0
    private var reportedError: String? = null
    private val colors = IntArray(36)
    private val output = GlyphFrameOutput { mask ->
        val instance = manager ?: error("Glyph session is not connected")
        for (channel in colors.indices) colors[channel] = if (mask and (1L shl channel) != 0L) 4000 else 0
        // toggle()/turnOff() enqueue SDK tasks. This API writes the complete frame directly.
        instance.setFrameColors(colors)
    }
    private var status: ((String) -> Unit)? = null

    @Synchronized
    fun init(context: Context, deviceModel: String, onStatus: ((String) -> Unit)? = null,
             owner: Any = defaultOwner, onConnected: (() -> Unit)? = null) {
        if (!Common.is24111() || deviceModel != Glyph.DEVICE_24111) {
            onStatus?.invoke("Glyph requires Nothing Phone (3a) or (3a) Pro")
            return
        }
        if (manager != null && currentOwner !== owner) return
        currentOwner = owner
        reportedError = null
        status = onStatus
        onReady = onConnected
        if (manager != null) {
            status?.invoke(if (ready) "Glyph connected" else "Connecting to Glyph…")
            if (ready) onReady?.invoke()
            return
        }
        val token = ++generation
        // The SDK singleton retains its callback even after unInit(). Do not capture an Activity/Service.
        val ownerRef = WeakReference(owner)
        status?.invoke("Connecting to Glyph…")
        try {
            val instance = GlyphManager.getInstance(context.applicationContext)
            manager = instance
            instance.init(object : GlyphManager.Callback {
                override fun onServiceConnected(componentName: ComponentName?) {
                    runOnMainThread {
                        synchronized(this@GlyphHelper) {
                            if (token != generation) return@runOnMainThread
                        try {
                            check(instance.register(deviceModel)) { "Glyph registration was denied" }
                            instance.openSession()
                            ready = true
                            output.reset()
                            status?.invoke("Glyph connected")
                            onReady?.invoke()
                        } catch (e: Exception) {
                            reportError(e)
                            ownerRef.get()?.let { release(it) }
                        }
                        }
                    }
                }
                override fun onServiceDisconnected(componentName: ComponentName?) {
                    runOnMainThread {
                        synchronized(this@GlyphHelper) {
                            if (token == generation) {
                            ready = false
                            status?.invoke("Glyph disconnected")
                        }
                        }
                    }
                }
            })
        } catch (e: Exception) {
            reportError(e)
            release(owner)
        }
    }

    @Synchronized
    fun release(owner: Any = defaultOwner) {
        if (currentOwner !== owner) return
        ++generation // Ignore callbacks from an old/pending connection.
        val instance = manager
        manager = null
        currentOwner = defaultOwner
        ready = false
        output.reset()
        status = null
        onReady = null
        if (instance != null) {
            try { colors.fill(0); instance.setFrameColors(colors); instance.closeSession() }
            catch (e: Exception) { Log.w("GlyphController", "Closing Glyph session", e) }
            finally {
                try { instance.unInit() }
                catch (e: Exception) { Log.w("GlyphController", "Unbinding Glyph", e) }
            }
        }
    }

    @Synchronized
    fun turnOffTheLight(owner: Any = defaultOwner) {
        if (!ready || currentOwner !== owner) return
        try { output.show(0L) } catch (e: Exception) { reportError(e) }
    }

    // One call produces one frame. There is no completion callback that can recursively render.
    @Synchronized
    fun show(tick: GlyphTick, dot: Boolean = false, owner: Any = defaultOwner) {
        if (!ready || currentOwner !== owner) return
        display(GlyphLayout.stripMask(tick.lightA, 'a', tick.reverseA, dot) or
            GlyphLayout.stripMask(tick.lightB, 'b', tick.reverseB, dot) or
            GlyphLayout.stripMask(tick.lightC, 'c', tick.reverseC, dot))
    }

    @Synchronized
    fun showFlow(value: Float, start: Char, reverse: Boolean, dot: Boolean, owner: Any = defaultOwner) {
        if (!ready || currentOwner !== owner) return
        display(GlyphLayout.flowMask(value, start, reverse, dot))
    }

    @Synchronized
    fun blinkB() {
        if (!ready || currentOwner !== defaultOwner) return
        try {
            val instance = manager ?: return
            val builder = instance.glyphFrameBuilder.buildInterval(10).buildCycles(2).buildPeriod(3000)
            for (channel in 31..35) builder.buildChannel(channel)
            output.reset()
            instance.animate(builder.build())
        } catch (e: Exception) { reportError(e) }
    }

    private fun display(mask: Long) {
        try { output.show(mask) } catch (e: Exception) { reportError(e) }
    }

    private fun reportError(e: Exception) {
        val message = "Glyph error: ${e.message ?: e.javaClass.simpleName}"
        if (reportedError == message) return
        reportedError = message
        Log.e("GlyphController", "Glyph operation failed", e)
        val token = generation
        // Errors may originate on the FFT worker. Never call UI listeners from that worker.
        if (Looper.myLooper() == Looper.getMainLooper()) status?.invoke(message)
        else mainHandler.post {
            synchronized(this) { if (token == generation) status?.invoke(message) }
        }
    }
}
