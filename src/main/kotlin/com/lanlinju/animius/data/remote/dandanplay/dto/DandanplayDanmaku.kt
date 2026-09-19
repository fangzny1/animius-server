package com.lanlinju.animius.data.remote.dandanplay.dto

import kotlinx.serialization.Serializable

@Serializable
class DandanplayDanmaku(
    val cid: Long,
    val p: String,
    val m: String, // content
)

/*
p参数格式为出现时间,模式,颜色,用户ID，各个参数之间使用英文逗号分隔
弹幕出现时间：格式为 0.00，单位为秒，精确到小数点后两位，例如12.34、445.6、789.01
弹幕模式：1-普通弹幕，4-底部弹幕，5-顶部弹幕
颜色：32位整数表示的颜色，算法为 Rx256x256+Gx256+B，R/G/B的范围应是0-255
 */
data class DanmakuItem(
    val time: Double,
    val mode: Int,      // 1-滚动 4-底部 5-顶部
    val color: Int,
    val text: String,
)

fun DandanplayDanmaku.toDanmakuItemOrNull(): DanmakuItem? {
    val parts = p.split(",")
    if (parts.size < 4) return null
    val timeSecs = parts[0].toDoubleOrNull() ?: return null
    val mode = parts[1].toIntOrNull() ?: return null
    if (mode != 1 && mode != 4 && mode != 5) return null
    val color = parts[2].toIntOrNull() ?: return null
    return DanmakuItem(time = timeSecs, mode = mode, color = color, text = m)
}

@Serializable
class DandanplayDanmakuListResponse(
    val count: Int,
    val comments: List<DandanplayDanmaku>
)
