package org.npu.face_control.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.npu.face_control.FaceControlConfig
import org.npu.face_control.LiveMetrics
import kotlin.math.roundToInt

/** 单个灵敏度参数的描述 */
private data class ParamSpec(
    val label: String,
    val min: Float,
    val max: Float,
    val unit: String = "",
    val hint: String = "",
    val read: () -> Float,
    val write: (Float) -> Unit
)

private fun buildParams(context: Context): List<Pair<String, List<ParamSpec>>> {
    fun spec(
        label: String,
        min: Float,
        max: Float,
        unit: String = "",
        hint: String = "",
        read: () -> Float,
        write: (Float) -> Unit
    ) = ParamSpec(label, min, max, unit, hint, read, write)

    return listOf(
        "基础模式 - 眨眼" to listOf(
            spec("闭眼 EAR 阈值", 0.05f, 0.35f, hint = "越大越灵敏", read = { FaceControlConfig.config.blinkEarThreshold }, write = { v -> FaceControlConfig.update(context) { c -> c.blinkEarThreshold = v } }),
            spec("长闭眼时长", 300f, 2000f, "ms", "越短越灵敏", { FaceControlConfig.config.longBlinkMinMs.toFloat() }, { v -> FaceControlConfig.update(context) { c -> c.longBlinkMinMs = v.toLong() } }),
            spec("双眨眼最小间隔", 50f, 300f, "ms", "越小越灵敏", { FaceControlConfig.config.doubleBlinkMinInterval.toFloat() }, { v -> FaceControlConfig.update(context) { c -> c.doubleBlinkMinInterval = v.toLong() } }),
            spec("双眨眼最大间隔", 300f, 1500f, "ms", "越大越灵敏", { FaceControlConfig.config.doubleBlinkMaxInterval.toFloat() }, { v -> FaceControlConfig.update(context) { c -> c.doubleBlinkMaxInterval = v.toLong() } })
        ),
        "基础模式 - 摇头 / 点头" to listOf(
            spec("左扭头阈值", 0f, 1f, hint = "越小越灵敏", read = { FaceControlConfig.config.shakeLeftRatio }, write = { v -> FaceControlConfig.update(context) { c -> c.shakeLeftRatio = v } }),
            spec("右扭头阈值", 0f, 1f, hint = "越大越灵敏", read = { FaceControlConfig.config.shakeRightRatio }, write = { v -> FaceControlConfig.update(context) { c -> c.shakeRightRatio = v } }),
            spec("低头阈值", 0f, 1f, hint = "越小越灵敏", read = { FaceControlConfig.config.nodRatio }, write = { v -> FaceControlConfig.update(context) { c -> c.nodRatio = v } }),
            spec("抬头阈值", 0f, 1f, hint = "越大越灵敏", read = { FaceControlConfig.config.lookUpRatio }, write = { v -> FaceControlConfig.update(context) { c -> c.lookUpRatio = v } })
        ),
        "基础模式 - 张嘴" to listOf(
            spec("张嘴阈值", 0.10f, 0.90f, hint = "越小越灵敏", read = { FaceControlConfig.config.mouthOpenMar }, write = { v -> FaceControlConfig.update(context) { c -> c.mouthOpenMar = v } })
        ),
        "准心模式 - 光标" to listOf(
            spec("左移阈值", 0f, 1f, hint = "越小越灵敏", read = { FaceControlConfig.config.crosshairShakeLeftRatio }, write = { v -> FaceControlConfig.update(context) { c -> c.crosshairShakeLeftRatio = v } }),
            spec("右移阈值", 0f, 1f, hint = "越大越灵敏", read = { FaceControlConfig.config.crosshairShakeRightRatio }, write = { v -> FaceControlConfig.update(context) { c -> c.crosshairShakeRightRatio = v } }),
            spec("下移阈值", 0f, 1f, hint = "越小越灵敏", read = { FaceControlConfig.config.crosshairNodRatio }, write = { v -> FaceControlConfig.update(context) { c -> c.crosshairNodRatio = v } }),
            spec("上移阈值", 0f, 1f, hint = "越大越灵敏", read = { FaceControlConfig.config.crosshairLookUpRatio }, write = { v -> FaceControlConfig.update(context) { c -> c.crosshairLookUpRatio = v } }),
            spec("光标步长", 4f, 40f, "px", "越大移动越快", { FaceControlConfig.config.crosshairStepPx }, { v -> FaceControlConfig.update(context) { c -> c.crosshairStepPx = v } })
        ),
        "连续摆头确认" to listOf(
            spec("确认延迟", 300f, 1500f, "ms", "越短单次响应越快，越长越易识别连续摆头", { FaceControlConfig.config.shakeConfirmDelayMs.toFloat() }, { v -> FaceControlConfig.update(context) { c -> c.shakeConfirmDelayMs = v.toLong() } })
        )
    )
}

@Composable
fun SettingsScreen(
    context: Context,
    onBack: () -> Unit,
    onOpenTest: () -> Unit
) {
    val groups = remember { buildParams(context) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("灵敏度设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = onOpenTest) { Text("测试") }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Text(
                "双向阈值（左右、上下）之间的区间为死区（不响应）；请保持左/下阈值大于右/上阈值，若调反，系统会自动拉开最小死区。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(Modifier.height(12.dp))
            groups.forEach { (group, params) ->
                Text(group, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                params.forEach { ParamSlider(it) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ParamSlider(param: ParamSpec) {
    var value by remember { mutableStateOf(param.read()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(param.label, style = MaterialTheme.typography.bodyMedium)
            Text(formatValue(param, value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = {
                value = it
                param.write(it)
            },
            valueRange = param.min..param.max
        )
        if (param.hint.isNotEmpty()) {
            Text(param.hint, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

private fun formatValue(param: ParamSpec, v: Float): String {
    return if (param.unit == "ms" || param.unit == "px") {
        "${v.roundToInt()}${param.unit}"
    } else {
        String.format("%.2f", v)
    }
}

private data class MetricsSnapshot(
    val tracking: Boolean,
    val ear: Float,
    val earThreshold: Float,
    val shakeRatio: Float,
    val shakeLeft: Float,
    val shakeRight: Float,
    val nodRatio: Float,
    val nodDown: Float,
    val nodUp: Float,
    val mar: Float,
    val mouthOpen: Float,
    val lastAction: String
)

private fun snapshotMetrics(): MetricsSnapshot {
    val c = FaceControlConfig.config
    return MetricsSnapshot(
        tracking = LiveMetrics.isTracking,
        ear = LiveMetrics.ear,
        earThreshold = LiveMetrics.earThreshold,
        shakeRatio = LiveMetrics.shakeRatio,
        shakeLeft = c.shakeLeftRatio,
        shakeRight = c.shakeRightRatio,
        nodRatio = LiveMetrics.nodRatio,
        nodDown = c.nodRatio,
        nodUp = c.lookUpRatio,
        mar = LiveMetrics.mar,
        mouthOpen = c.mouthOpenMar,
        lastAction = LiveMetrics.lastAction
    )
}

@Composable
fun TestScreen(onBack: () -> Unit) {
    var m by remember { mutableStateOf(snapshotMetrics()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            m = snapshotMetrics()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("灵敏度测试", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("返回") }
        }
        if (!m.tracking) {
            Text("未检测到人脸，请对准摄像头", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("最近动作：${m.lastAction}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))

            MetricCard(
                "EAR（眨眼）",
                m.ear,
                m.earThreshold,
                0f, 0.5f,
                triggered = m.ear < m.earThreshold,
                hint = "EAR 低于阈值即闭眼"
            )
            Spacer(Modifier.height(12.dp))

            DualThresholdCard("摇头横向比例", m.shakeRatio, m.shakeLeft, m.shakeRight, "右", "左")
            Spacer(Modifier.height(12.dp))

            DualThresholdCard("点头纵向比例", m.nodRatio, m.nodDown, m.nodUp, "上", "下")
            Spacer(Modifier.height(12.dp))

            MetricCard(
                "MAR（张嘴）",
                m.mar,
                m.mouthOpen,
                0f, 1f,
                triggered = m.mar > m.mouthOpen,
                hint = "MAR 高于阈值即张嘴"
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: Float,
    threshold: Float,
    min: Float,
    max: Float,
    triggered: Boolean,
    hint: String
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatFloat(value)} / 阈值 ${formatFloat(threshold)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (triggered) Color(0xFF00C853) else Color.Gray,
                fontWeight = if (triggered) FontWeight.Bold else FontWeight.Normal
            )
        }
        IndicatorBar(value, threshold, min, max, Color(0xFF00C853))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun DualThresholdCard(
    label: String,
    value: Float,
    highThreshold: Float,
    lowThreshold: Float,
    lowName: String,
    highName: String
) {
    val triggered = value > highThreshold || value < lowThreshold
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatFloat(value),
                style = MaterialTheme.typography.bodyMedium,
                color = if (triggered) Color(0xFF00C853) else Color.Gray,
                fontWeight = if (triggered) FontWeight.Bold else FontWeight.Normal
            )
        }
        Text(
            "$lowName 阈值 ${formatFloat(lowThreshold)}　·　$highName 阈值 ${formatFloat(highThreshold)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        IndicatorBar(value, highThreshold, 0f, 1f, Color(0xFF00C853))
        IndicatorBar(value, lowThreshold, 0f, 1f, Color(0xFF448AFF))
    }
}

/** 一条灰底条，绿色竖线标当前值，红色竖线标阈值 */
@Composable
private fun IndicatorBar(value: Float, threshold: Float, min: Float, max: Float, valueColor: Color) {
    val range = (max - min).coerceAtLeast(0.0001f)
    val valueFrac = ((value - min) / range).coerceIn(0f, 1f)
    val thFrac = ((threshold - min) / range).coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFEEEEEE))
    ) {
        val w = maxWidth
        // 阈值线（红）
        Box(
            Modifier.offset(x = (w * thFrac) - 1.5.dp)
                .width(3.dp).fillMaxHeight()
                .background(Color(0xFFFF3B30))
        )
        // 当前值线（绿/蓝）
        Box(
            Modifier.offset(x = (w * valueFrac) - 2.dp)
                .width(4.dp).fillMaxHeight()
                .background(valueColor)
        )
    }
}

private fun formatFloat(v: Float): String = String.format("%.3f", v)
