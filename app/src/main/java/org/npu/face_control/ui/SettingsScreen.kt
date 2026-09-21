package org.npu.face_control.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.npu.face_control.FaceAnalyzer.FaceAction
import org.npu.face_control.config.AppConfig
import org.npu.face_control.config.ConfigRepository
import org.npu.face_control.config.GestureBehavior
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 设置页可配置的动作（BLINK 不派发，不展示） */
private val ACTION_KEYS = listOf(
    FaceAction.DOUBLE_BLINK,
    FaceAction.LONG_BLINK,
    FaceAction.HEAD_DOWN,
    FaceAction.LOOK_UP,
    FaceAction.SHAKE_LEFT,
    FaceAction.SHAKE_RIGHT,
    FaceAction.MOUTH_OPEN,
    FaceAction.MOUTH_CLOSE
)

private fun actionLabel(action: FaceAction): String = when (action) {
    FaceAction.DOUBLE_BLINK -> "双眨眼"
    FaceAction.LONG_BLINK -> "长闭眼"
    FaceAction.HEAD_DOWN -> "低头"
    FaceAction.LOOK_UP -> "抬头"
    FaceAction.SHAKE_LEFT -> "向左扭头"
    FaceAction.SHAKE_RIGHT -> "向右扭头"
    FaceAction.MOUTH_OPEN -> "张嘴"
    FaceAction.MOUTH_CLOSE -> "闭嘴"
    FaceAction.BLINK -> "眨眼"
}

/**
 * 手势自定义设置页：自定义模式开关、竖屏/横屏/准心三套映射、按动作的灵敏度、恢复默认。
 * 改动即时写入 [ConfigRepository]，运行中的服务下一帧/下一动作即生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)

    var config by remember { mutableStateOf(ConfigRepository.stored()) }
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("手势设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 自定义模式开关 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自定义模式", fontWeight = FontWeight.Bold)
                        Text(
                            if (config.customModeEnabled) "已开启：使用下面的映射与灵敏度"
                            else "已关闭：使用内置默认手势",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.customModeEnabled,
                        onCheckedChange = { on ->
                            config = config.copy(customModeEnabled = on)
                            ConfigRepository.save(config)
                        }
                    )
                }
            }

            // ---------- 映射表：竖屏 / 横屏 / 准心 ----------
            Text("手势映射", fontWeight = FontWeight.Bold)
            TabRow(selectedTabIndex = tab) {
                listOf("竖屏", "横屏", "准心").forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) }
                    )
                }
            }

            val currentMap = when (tab) {
                0 -> config.portraitMap
                1 -> config.landscapeMap
                else -> config.crosshairMap
            }
            ACTION_KEYS.forEach { action ->
                ActionMappingRow(
                    label = actionLabel(action),
                    selected = currentMap[action] ?: GestureBehavior.NONE
                ) { behavior ->
                    val newMap = currentMap.toMutableMap().apply { put(action, behavior) }
                    config = when (tab) {
                        0 -> config.copy(portraitMap = newMap)
                        1 -> config.copy(landscapeMap = newMap)
                        else -> config.copy(crosshairMap = newMap)
                    }
                    ConfigRepository.save(config)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ---------- 灵敏度 ----------
            Text("灵敏度", fontWeight = FontWeight.Bold)

            val s = config.sensitivity
            fun saveSensitivity(new: org.npu.face_control.config.SensitivitySettings) {
                config = config.copy(sensitivity = new)
            }

            SensitivitySliderRow(
                title = "闭眼灵敏度",
                value = s.earCloseSensitivity,
                valueRange = 0.10f..0.90f,
                steps = 15,
                valueText = "%.2f".format(s.earCloseSensitivity),
                onValueChange = { saveSensitivity(s.copy(earCloseSensitivity = it)) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )
            SensitivitySliderRow(
                title = "长闭眼时长 (ms)",
                value = s.longBlinkMs.toFloat(),
                valueRange = 500f..4000f,
                steps = 34,
                valueText = "${s.longBlinkMs}",
                onValueChange = { saveSensitivity(s.copy(longBlinkMs = it.roundToLong())) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )
            SensitivitySliderRow(
                title = "双眨眼间隔 (ms)",
                value = s.doubleBlinkWindowMs.toFloat(),
                valueRange = 300f..1500f,
                steps = 23,
                valueText = "${s.doubleBlinkWindowMs}",
                onValueChange = { saveSensitivity(s.copy(doubleBlinkWindowMs = it.roundToLong())) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )
            SensitivitySliderRow(
                title = "摇头灵敏度",
                value = s.shakeSensitivity,
                valueRange = 0.10f..0.90f,
                steps = 15,
                valueText = "%.2f".format(s.shakeSensitivity),
                onValueChange = { saveSensitivity(s.copy(shakeSensitivity = it)) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )
            SensitivitySliderRow(
                title = "点头灵敏度",
                value = s.nodSensitivity,
                valueRange = 0.10f..0.90f,
                steps = 15,
                valueText = "%.2f".format(s.nodSensitivity),
                onValueChange = { saveSensitivity(s.copy(nodSensitivity = it)) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )
            SensitivitySliderRow(
                title = "张嘴阈值 MAR",
                value = s.mouthOpenMar,
                valueRange = 0.20f..0.90f,
                steps = 13,
                valueText = "%.2f".format(s.mouthOpenMar),
                onValueChange = { saveSensitivity(s.copy(mouthOpenMar = it)) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )
            SensitivitySliderRow(
                title = "准心移动速度",
                value = s.crosshairSpeedPx,
                valueRange = 4f..40f,
                steps = 17,
                valueText = "${s.crosshairSpeedPx.roundToInt()}",
                onValueChange = { saveSensitivity(s.copy(crosshairSpeedPx = it)) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )
            SensitivitySliderRow(
                title = "准心偏头灵敏度",
                value = s.crosshairShakeSensitivity,
                valueRange = 0.10f..0.90f,
                steps = 15,
                valueText = "%.2f".format(s.crosshairShakeSensitivity),
                onValueChange = { saveSensitivity(s.copy(crosshairShakeSensitivity = it)) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )
            SensitivitySliderRow(
                title = "准心点头灵敏度",
                value = s.crosshairNodSensitivity,
                valueRange = 0.10f..0.90f,
                steps = 15,
                valueText = "%.2f".format(s.crosshairNodSensitivity),
                onValueChange = { saveSensitivity(s.copy(crosshairNodSensitivity = it)) },
                onValueChangeFinished = { ConfigRepository.save(config) }
            )

            // ---------- 恢复默认 ----------
            OutlinedButton(
                onClick = {
                    ConfigRepository.resetToDefault()
                    config = ConfigRepository.stored()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复默认设置")
            }
        }
    }
}

@Composable
private fun ActionMappingRow(
    label: String,
    selected: GestureBehavior,
    onSelected: (GestureBehavior) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        BehaviorDropdown(selected = selected, onSelected = onSelected)
    }
}

@Composable
private fun BehaviorDropdown(
    selected: GestureBehavior,
    onSelected: (GestureBehavior) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GestureBehavior.ALL.forEach { behavior ->
                DropdownMenuItem(
                    text = { Text(behavior.label) },
                    onClick = {
                        onSelected(behavior)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SensitivitySliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontSize = 14.sp)
            Text(valueText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}
