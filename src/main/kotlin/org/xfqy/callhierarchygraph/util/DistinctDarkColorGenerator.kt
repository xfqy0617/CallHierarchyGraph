package org.xfqy.callhierarchygraph.util

import java.awt.Color
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random


/**
 * 深色颜色生成器
 *
 * 生成与已有颜色集在视觉上差异较大的随机深色。
 */
object DistinctDarkColorGenerator {

    // --- 可配置参数 ---

    // 生成多少个候选颜色进行比较，选择最优的那个。数值越大，找到最优解的概率越高，但性能开销越大。
    private const val CANDIDATE_COUNT = 50

    // 定义“深色”的范围
    private const val MIN_BRIGHTNESS = 0.55f // 亮度下限，避免太接近黑色
    private const val MAX_BRIGHTNESS = 0.80f // 亮度上限，避免颜色过亮
    private const val MIN_SATURATION = 0.70f // 饱和度下限，避免颜色发灰
    private const val MAX_SATURATION = 1.0f  // 饱和度上限

    // HSB 颜色模型的数据类
    private data class HsbColor(val h: Float, val s: Float, val b: Float)

    /**
     * 主方法：生成一个新的、与已有颜色不同的深色。
     * @param existingColorsHex 一个包含已有颜色十六进制字符串的 Set (例如 "#ff0000").
     * @return 一个新的深色十六进制字符串 (例如 "#3a88a8").
     */
    fun generate(existingColorsHex: Set<String>): String {
        // 如果已有颜色为空，直接返回一个随机的深色
        if (existingColorsHex.isEmpty()) {
            val randomHsb = generateRandomDarkHsb()
            return hsbToHex(randomHsb)
        }

        // 将已有的十六进制颜色转换为 HSB 格式，方便计算
        val existingHsbColors = existingColorsHex.mapNotNull { hexToHsb(it) }

        var bestCandidate: HsbColor? = null
        var maxMinDistance = -1f

        // 生成一组候选颜色
        repeat(CANDIDATE_COUNT) {
            val candidate = generateRandomDarkHsb()

            // 计算该候选颜色与所有已有颜色的最小距离
            val minDistance = existingHsbColors.minOfOrNull { existingColor ->
                calculateDistance(candidate, existingColor)
            } ?: Float.MAX_VALUE // 如果 existingHsbColors 为空则距离为无穷大

            // 如果这个最小距离比我们之前找到的“最大-最小距离”还要大，
            // 说明这个候选颜色是目前为止最优的选择
            if (minDistance > maxMinDistance) {
                maxMinDistance = minDistance
                bestCandidate = candidate
            }
        }

        // 返回找到的最佳颜色的十六进制字符串
        return hsbToHex(bestCandidate ?: generateRandomDarkHsb())
    }

    /**
     * 计算两个 HSB 颜色之间的加权距离。
     * 色相(H)的权重最高，因为它对视觉差异的贡献最大。
     */
    private fun calculateDistance(c1: HsbColor, c2: HsbColor): Float {
        // 色相是环形的 (0~1)，所以距离要取最短路径
        val hueDistance = min(abs(c1.h - c2.h), 1f - abs(c1.h - c2.h))
        val saturationDistance = abs(c1.s - c2.s)
        val brightnessDistance = abs(c1.b - c2.b)

        // 色相权重为3，饱和度和亮度权重为1
        return 3 * hueDistance + saturationDistance + brightnessDistance
    }

    /**
     * 生成一个在预设范围内的随机深色 HSB 颜色。
     */
    private fun generateRandomDarkHsb(): HsbColor {
        val h = Random.nextFloat() // 色相 (0.0 to 1.0)
        val s = Random.nextFloat() * (MAX_SATURATION - MIN_SATURATION) + MIN_SATURATION
        val b = Random.nextFloat() * (MAX_BRIGHTNESS - MIN_BRIGHTNESS) + MIN_BRIGHTNESS
        return HsbColor(h, s, b)
    }

    // --- 颜色格式转换辅助函数 ---

    private fun hsbToHex(hsb: HsbColor): String {
        val rgb = Color.HSBtoRGB(hsb.h, hsb.s, hsb.b)
        val color = Color(rgb)
        // 格式化为 #RRGGBB
        return String.format("#%02x%02x%02x", color.red, color.green, color.blue)
    }

    private fun hexToHsb(hex: String): HsbColor? {
        return try {
            val color = Color.decode(hex)
            val hsbvals = FloatArray(3)
            Color.RGBtoHSB(color.red, color.green, color.blue, hsbvals)
            HsbColor(hsbvals[0], hsbvals[1], hsbvals[2])
        } catch (e: NumberFormatException) {
            // 如果传入了无效的十六进制字符串，则忽略
            null
        }
    }
}