package ink.enoch.glyphplayer.utils

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager
import kotlin.math.ceil

/** Phone (3a)/(3a) Pro share the 24111 layout: A=11, B=5, C=20 LEDs. */
object GlyphHelper {
    data class GlyphTick(
        var lightA: Float, var lightB: Float, var lightC: Float,
        val reverseA: Boolean, val reverseB: Boolean, val reverseC: Boolean
    )

    private var manager: GlyphManager? = null
    private var ready = false
    private var generation = 0
    private var status: ((String) -> Unit)? = null
    private val strips = mapOf('a' to (20..30).toList(), 'b' to (31..35).toList(), 'c' to (0..19).toList())

    fun init(context: Context, deviceModel: String, onStatus: ((String) -> Unit)? = null) {
        if (!Common.is24111() || deviceModel != Glyph.DEVICE_24111) {
            onStatus?.invoke("Glyph requires Nothing Phone (3a) or (3a) Pro")
            return
        }
        status = onStatus
        if (manager != null) {
            status?.invoke(if (ready) "Glyph connected" else "Connecting to Glyph…")
            return
        }
        val token = ++generation
        status?.invoke("Connecting to Glyph…")
        try {
            val instance = GlyphManager.getInstance(context.applicationContext)
            manager = instance
            instance.init(object : GlyphManager.Callback {
                override fun onServiceConnected(componentName: ComponentName?) {
                    runOnMainThread {
                        if (token != generation) return@runOnMainThread
                        try {
                            check(instance.register(deviceModel)) { "Glyph registration was denied" }
                            instance.openSession()
                            ready = true
                            status?.invoke("Glyph connected")
                        } catch (e: Exception) {
                            reportError(e)
                            release()
                        }
                    }
                }
                override fun onServiceDisconnected(componentName: ComponentName?) {
                    runOnMainThread {
                        if (token == generation) {
                            ready = false
                            status?.invoke("Glyph disconnected")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            reportError(e)
            release()
        }
    }

    fun release() {
        ++generation // Ignore callbacks from an old/pending connection.
        val instance = manager
        manager = null
        ready = false
        status = null
        if (instance != null) {
            try { instance.turnOff(); instance.closeSession() }
            catch (e: Exception) { Log.w("GlyphController", "Closing Glyph session", e) }
            finally {
                try { instance.unInit() }
                catch (e: Exception) { Log.w("GlyphController", "Unbinding Glyph", e) }
            }
        }
    }

    fun turnOffTheLight() {
        if (!ready) return
        try { manager?.turnOff() } catch (e: Exception) { reportError(e) }
    }

    private fun level(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private fun channels(value: Float, leds: List<Int>, dot: Boolean): List<Int> {
        val amount = level(value)
        if (amount <= 0f) return emptyList()
        return if (dot) listOf(leds[(amount * leds.size).toInt().coerceAtMost(leds.lastIndex)])
        else leds.take(ceil(amount * leds.size).toInt())
    }

    fun show(tick: GlyphTick, dot: Boolean = false, onFinish: () -> Unit = {}) {
        // Dropping a frame during async initialization must never block future frames.
        if (!ready) return
        val leds = channels(tick.lightA, strips.getValue('a').let { if (tick.reverseA) it.reversed() else it }, dot) +
            channels(tick.lightB, strips.getValue('b').let { if (tick.reverseB) it.reversed() else it }, dot) +
            channels(tick.lightC, strips.getValue('c').let { if (tick.reverseC) it.reversed() else it }, dot)
        display(leds)
        onFinish()
    }

    fun showFlow(value: Float, start: Char, reverse: Boolean, dot: Boolean, onFinish: () -> Unit = {}) {
        if (!ready) return
        val order = when (start.lowercaseChar()) {
            'a' -> if (reverse) "acb" else "abc"
            'b' -> if (reverse) "bac" else "bca"
            'c' -> if (reverse) "cba" else "cab"
            else -> return
        }
        val leds = order.flatMap { strips.getValue(it).let { strip -> if (reverse) strip.reversed() else strip } }
        display(channels(value, leds, dot))
        onFinish()
    }

    private fun display(leds: List<Int>) {
        try {
            val instance = manager ?: return
            if (leds.isEmpty()) {
                instance.turnOff()
            } else {
                val builder = instance.glyphFrameBuilder
                leds.forEach { builder.buildChannel(it) }
                instance.toggle(builder.build())
            }
        } catch (e: Exception) { reportError(e) }
    }

    private fun reportError(e: Exception) {
        Log.e("GlyphController", "Glyph operation failed", e)
        status?.invoke("Glyph error: ${e.message ?: e.javaClass.simpleName}")
    }
}
