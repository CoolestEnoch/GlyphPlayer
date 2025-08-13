package ink.enoch.glyphplayer.utils

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager

object GlyphHelper {

    // Glyph控制参数数据类
    data class GlyphTick(
        var lightA: Float,   // A灯条点亮比例 (0-1)
        var lightB: Float,   // B灯条点亮比例 (0-1)
        var lightC: Float,   // C灯条点亮比例 (0-1)
        val reverseA: Boolean, // A灯条是否反向点亮
        val reverseB: Boolean, // B灯条是否反向点亮
        val reverseC: Boolean  // C灯条是否反向点亮
    )


    private const val TAG = "GlyphController"
    private var glyphManager: GlyphManager? = null
    private var isInitialized = false
    private var isPresenting = false
    private val glyphLogEnable = false

    // 设备型号的LED配置
    private data class DeviceConfig(
        val aRange: IntRange,   // A灯条索引范围
        val bRange: IntRange,   // B灯条索引范围
        val cRange: IntRange,   // C灯条索引范围
        val aCount: Int,        // A灯条LED数量
        val bCount: Int,        // B灯条LED数量
        val cCount: Int         // C灯条LED数量
    )

    // 支持的设备配置（目前只支持24111）
    private val supportedDevices = mapOf(
        Glyph.DEVICE_24111 to DeviceConfig(
            aRange = 20..30, // A1-A11 (11个)
            bRange = 31..35, // B1-B5 (5个)
            cRange = 0..19,  // C1-C20 (20个)
            aCount = 11,
            bCount = 5,
            cCount = 20
        )
    )

    private var currentConfig: DeviceConfig? = null

    // 初始化Glyph系统
    fun init(context: Context, deviceModel: String) {
        if (isInitialized) {
            Log.w(TAG, "GlyphController already initialized")
            return
        }

        currentConfig = supportedDevices[deviceModel]
        if (currentConfig == null) {
            Log.e(TAG, "Unsupported device model: $deviceModel")
            return
        }

        glyphManager = GlyphManager.getInstance(context.applicationContext)
        glyphManager?.init(object : GlyphManager.Callback {
            override fun onServiceConnected(componentName: ComponentName?) {
                try {
                    glyphManager?.register(deviceModel)
                    glyphManager?.openSession()
                    isInitialized = true
                    Log.i(TAG, "Glyph service connected and session opened")
                } catch (e: GlyphException) {
                    Log.e(TAG, "Error opening session: ${e.message}")
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                isInitialized = false
                Log.w(TAG, "Glyph service disconnected")
            }
        })
    }

    // 释放资源
    fun release() {
        if (!isInitialized) return

        try {
            glyphManager?.closeSession()
            glyphManager?.unInit()
            glyphManager = null
            isInitialized = false
            Log.i(TAG, "Glyph resources released")
        } catch (e: GlyphException) {
            Log.e(TAG, "Error releasing resources: ${e.message}")
        }
    }

    // 临时关闭所有灯
    fun turnOffTheLight() {
        glyphManager?.turnOff()
    }

    // 显示Glyph效果 - 原始版本
    fun show(tick: GlyphTick, dot: Boolean = false, onFinish: () -> Unit = {}) {
        if (!isPresenting) {
            isPresenting = true
            if (dot) {
                showDot(tick)
            } else {
                if (!isInitialized || glyphManager == null) {
                    Log.e(TAG, "GlyphController not initialized")
                    return
                }

                val config = currentConfig ?: return
                val builder = glyphManager!!.glyphFrameBuilder

                // 构建A灯条（11个LED）
                val aCount = (tick.lightA * config.aCount).toInt().coerceIn(0, config.aCount)
                val aIndices = if (aCount > 0) {
                    if (tick.reverseA) {
                        // 反向：从高地址开始取aCount个
                        (config.aRange.last downTo config.aRange.last - aCount + 1).toList()
                    } else {
                        // 正向：从低地址开始取aCount个
                        (config.aRange.first until config.aRange.first + aCount).toList()
                    }
                } else {
                    emptyList()
                }
                aIndices.forEach { builder.buildChannel(it) }

                // 构建B灯条（5个LED）
                val bCount = (tick.lightB * config.bCount).toInt().coerceIn(0, config.bCount)
                val bIndices = if (bCount > 0) {
                    if (tick.reverseB) {
                        (config.bRange.last downTo config.bRange.last - bCount + 1).toList()
                    } else {
                        (config.bRange.first until config.bRange.first + bCount).toList()
                    }
                } else {
                    emptyList()
                }
                bIndices.forEach { builder.buildChannel(it) }

                // 构建C灯条（20个LED）
                val cCount = (tick.lightC * config.cCount).toInt().coerceIn(0, config.cCount)
                val cIndices = if (cCount > 0) {
                    if (tick.reverseC) {
                        (config.cRange.last downTo config.cRange.last - cCount + 1).toList()
                    } else {
                        (config.cRange.first until config.cRange.first + cCount).toList()
                    }
                } else {
                    emptyList()
                }
                cIndices.forEach { builder.buildChannel(it) }

                try {
                    val frame = builder.build()
                    glyphManager?.toggle(frame)
                    if(glyphLogEnable)
                    Log.d(TAG, "Showing Glyph: A=$aCount, B=$bCount, C=$cCount")
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling glyph: ${e.message}")
                }
            }
            isPresenting = false
            onFinish()
        }
    }

    // 显示点效果（每个灯条只点亮一个灯珠）
    private fun showDot(tick: GlyphTick) {
        if (!isInitialized || glyphManager == null) {
            Log.e(TAG, "GlyphController not initialized")
            return
        }

        val config = currentConfig ?: return
        val builder = glyphManager!!.glyphFrameBuilder

        // 构建A灯条点效果
        if (tick.lightA > 0f) {
            val aIndex = (tick.lightA * config.aCount).toInt().coerceIn(0, config.aCount - 1)
            val aLed = if (tick.reverseA) {
                config.aRange.last - aIndex
            } else {
                config.aRange.first + aIndex
            }
            builder.buildChannel(aLed)
        }

        // 构建B灯条点效果
        if (tick.lightB > 0f) {
            val bIndex = (tick.lightB * config.bCount).toInt().coerceIn(0, config.bCount - 1)
            val bLed = if (tick.reverseB) {
                config.bRange.last - bIndex
            } else {
                config.bRange.first + bIndex
            }
            builder.buildChannel(bLed)
        }

        // 构建C灯条点效果
        if (tick.lightC > 0f) {
            val cIndex = (tick.lightC * config.cCount).toInt().coerceIn(0, config.cCount - 1)
            val cLed = if (tick.reverseC) {
                config.cRange.last - cIndex
            } else {
                config.cRange.first + cIndex
            }
            builder.buildChannel(cLed)
        }

        try {
            val frame = builder.build()
            glyphManager?.toggle(frame)
            if(glyphLogEnable)
            Log.d(TAG, "Showing Dot: A=${tick.lightA}, B=${tick.lightB}, C=${tick.lightC}")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling dot glyph: ${e.message}")
        }
    }

    // 显示流动效果
    fun showFlow(
        value: Float,
        start: Char,
        reverse: Boolean,
        dot: Boolean,
        onFinish: () -> Unit = {}
    ) {
        if (!isPresenting) {
            isPresenting = true
            if (dot) {
                showFlowDot(value, start, reverse)
            } else {
                if (!isInitialized || glyphManager == null) {
                    Log.e(TAG, "GlyphController not initialized")
                    return
                }

                val config = currentConfig ?: return
                val builder = glyphManager!!.glyphFrameBuilder

                // 计算总LED数量
                val totalLeds = config.aCount + config.bCount + config.cCount
                val ledCount = (value * totalLeds).toInt().coerceIn(0, totalLeds)

                // 确定灯条顺序（不区分大小写）
                val order = when (start.toLowerCase()) {
                    'a' -> if (reverse) listOf('a', 'c', 'b') else listOf('a', 'b', 'c')
                    'b' -> if (reverse) listOf('b', 'a', 'c') else listOf('b', 'c', 'a')
                    'c' -> if (reverse) listOf('c', 'b', 'a') else listOf('c', 'a', 'b')
                    else -> {
                        Log.e(TAG, "Invalid start character: $start")
                        return
                    }
                }

                var remainingLeds = ledCount
                val stripCounts = mutableMapOf(
                    'a' to 0,
                    'b' to 0,
                    'c' to 0
                )

                // 按顺序分配LED数量
                for (strip in order) {
                    if (remainingLeds <= 0) break

                    val maxForStrip = when (strip) {
                        'a' -> config.aCount
                        'b' -> config.bCount
                        'c' -> config.cCount
                        else -> 0
                    }

                    val countForStrip = minOf(remainingLeds, maxForStrip)
                    stripCounts[strip] = countForStrip
                    remainingLeds -= countForStrip
                }

                // 构建灯条效果
                for ((strip, count) in stripCounts) {
                    if (count <= 0) continue

                    val range = when (strip) {
                        'a' -> config.aRange
                        'b' -> config.bRange
                        'c' -> config.cRange
                        else -> continue
                    }

                    val indices = if (reverse) {
                        // 反向：从高地址开始取count个
                        (range.last downTo range.last - count + 1).toList()
                    } else {
                        // 正向：从低地址开始取count个
                        (range.first until range.first + count).toList()
                    }

                    indices.forEach { builder.buildChannel(it) }
                }

                try {
                    val frame = builder.build()
                    glyphManager?.toggle(frame)
                    if(glyphLogEnable)
                    Log.d(
                        TAG,
                        "Showing Flow: start=$start, reverse=$reverse, value=$value, ledCount=$ledCount"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error toggling flow glyph: ${e.message}")
                }
            }
            isPresenting = false
            onFinish()
        }
    }

    // 显示流动点效果（只点亮一个灯珠）
    private fun showFlowDot(value: Float, start: Char, reverse: Boolean = false) {
        if (!isInitialized || glyphManager == null) {
            Log.e(TAG, "GlyphController not initialized")
            return
        }

        val config = currentConfig ?: return
        val builder = glyphManager!!.glyphFrameBuilder

        // 计算总LED数量
        val totalLeds = config.aCount + config.bCount + config.cCount
        if (totalLeds <= 0) return

        // 计算目标灯珠位置 (0 到 totalLeds-1)
        val targetIndex = (value * totalLeds).toInt().coerceIn(0, totalLeds - 1)

        // 确定灯条顺序（不区分大小写）
        val order = when (start.toLowerCase()) {
            'a' -> if (reverse) listOf('a', 'c', 'b') else listOf('a', 'b', 'c')
            'b' -> if (reverse) listOf('b', 'a', 'c') else listOf('b', 'c', 'a')
            'c' -> if (reverse) listOf('c', 'b', 'a') else listOf('c', 'a', 'b')
            else -> {
                Log.e(TAG, "Invalid start character: $start")
                return
            }
        }

        // 计算每个灯条的累积长度
        val stripLengths = mutableMapOf(
            'a' to config.aCount,
            'b' to config.bCount,
            'c' to config.cCount
        )

        var currentPosition = 0
        var targetStrip: Char? = null
        var localIndex = 0

        // 确定目标灯珠所在的灯条和局部位置
        for (strip in order) {
            val stripLength = stripLengths[strip] ?: 0
            if (targetIndex < currentPosition + stripLength) {
                targetStrip = strip
                localIndex = targetIndex - currentPosition
                break
            }
            currentPosition += stripLength
        }

        // 点亮目标灯珠
        targetStrip?.let { strip ->
            val range = when (strip) {
                'a' -> config.aRange
                'b' -> config.bRange
                'c' -> config.cRange
                else -> return@let
            }

            val ledIndex = if (reverse) {
                // 反向：从高地址开始
                range.last - localIndex
            } else {
                // 正向：从低地址开始
                range.first + localIndex
            }

            builder.buildChannel(ledIndex)
        }

        try {
            val frame = builder.build()
            glyphManager?.toggle(frame)
            if(glyphLogEnable)
            Log.d(
                TAG,
                "Showing Flow Dot: start=$start, reverse=$reverse, value=$value, targetIndex=$targetIndex"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flow dot glyph: ${e.message}")
        }
    }
}
