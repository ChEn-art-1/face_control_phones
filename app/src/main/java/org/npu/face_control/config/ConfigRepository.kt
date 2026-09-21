package org.npu.face_control.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import org.npu.face_control.FaceAnalyzer.FaceAction

/**
 * 进程级配置仓库：Activity 与两个 Service 同进程共享。
 *
 * 设计：持久化用 SharedPreferences + org.json（零新增依赖）；对外暴露 @Volatile 快照，
 * 消费端（FaceAnalyzer 每帧、服务每动作）直接读快照即可热生效，无需通知机制。
 */
object ConfigRepository {
    private const val TAG = "ConfigRepository"
    private const val PREFS_NAME = "face_control_settings"
    private const val KEY_JSON = "app_config_json"

    /** 用户在设置页编辑并保存的原始配置 */
    @Volatile private var storedConfig: AppConfig = AppConfig.DEFAULT

    /** 实际生效配置（自定义模式关闭时回落内置默认） */
    @Volatile private var effectiveConfig: AppConfig = AppConfig.DEFAULT

    /** 运行时阈值快照，供 FaceAnalyzer 每帧读取 */
    @Volatile var tuning: RuntimeTuning = RuntimeTuning.DEFAULT
        private set

    @Volatile private var prefs: SharedPreferences? = null

    /** 幂等初始化；MainActivity.onCreate 与 FaceControlForegroundService.onCreate 都调用。 */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val loaded = runCatching { parse(p.getString(KEY_JSON, null)) }
                .getOrElse {
                    Log.e(TAG, "配置解析失败，使用默认值", it)
                    AppConfig.DEFAULT
                }
            storedConfig = loaded
            prefs = p
            recompute()
            Log.i(TAG, "配置已加载: customMode=${loaded.customModeEnabled}")
        }
    }

    /** 设置页编辑用的原始配置 */
    fun stored(): AppConfig = storedConfig

    /** 运行时实际生效的配置 */
    fun effective(): AppConfig = effectiveConfig

    fun save(config: AppConfig) {
        storedConfig = config
        recompute()
        prefs?.edit()?.putString(KEY_JSON, encode(config).toString())?.apply()
    }

    fun resetToDefault() {
        storedConfig = AppConfig.DEFAULT
        recompute()
        prefs?.edit()?.remove(KEY_JSON)?.apply()
    }

    private fun recompute() {
        val c = storedConfig
        effectiveConfig = if (c.customModeEnabled) c else AppConfig.DEFAULT
        tuning = effectiveConfig.sensitivity.toTuning()
    }

    // ------------------------- JSON 编解码（org.json 随平台提供） -------------------------

    private fun encode(c: AppConfig): JSONObject = JSONObject().apply {
        put("customModeEnabled", c.customModeEnabled)
        put("sensitivity", JSONObject().apply {
            put("earCloseSensitivity", c.sensitivity.earCloseSensitivity.toDouble())
            put("longBlinkMs", c.sensitivity.longBlinkMs)
            put("doubleBlinkWindowMs", c.sensitivity.doubleBlinkWindowMs)
            put("shakeSensitivity", c.sensitivity.shakeSensitivity.toDouble())
            put("nodSensitivity", c.sensitivity.nodSensitivity.toDouble())
            put("mouthOpenMar", c.sensitivity.mouthOpenMar.toDouble())
            put("crosshairSpeedPx", c.sensitivity.crosshairSpeedPx.toDouble())
            put("crosshairShakeSensitivity", c.sensitivity.crosshairShakeSensitivity.toDouble())
            put("crosshairNodSensitivity", c.sensitivity.crosshairNodSensitivity.toDouble())
        })
        put("portrait", encodeMap(c.portraitMap))
        put("landscape", encodeMap(c.landscapeMap))
        put("crosshair", encodeMap(c.crosshairMap))
    }

    private fun encodeMap(m: Map<FaceAction, GestureBehavior>): JSONObject =
        JSONObject().apply { m.forEach { (a, b) -> put(a.name, b.name) } }

    private fun parse(raw: String?): AppConfig {
        if (raw.isNullOrBlank()) return AppConfig.DEFAULT
        val root = JSONObject(raw)
        val d = AppConfig.DEFAULT
        val sd = SensitivitySettings.DEFAULT
        val s = root.optJSONObject("sensitivity")
        return AppConfig(
            customModeEnabled = root.optBoolean("customModeEnabled", false),
            sensitivity = SensitivitySettings(
                earCloseSensitivity = s?.optDouble("earCloseSensitivity", sd.earCloseSensitivity.toDouble())?.toFloat() ?: sd.earCloseSensitivity,
                longBlinkMs = s?.optLong("longBlinkMs", sd.longBlinkMs) ?: sd.longBlinkMs,
                doubleBlinkWindowMs = s?.optLong("doubleBlinkWindowMs", sd.doubleBlinkWindowMs) ?: sd.doubleBlinkWindowMs,
                shakeSensitivity = s?.optDouble("shakeSensitivity", sd.shakeSensitivity.toDouble())?.toFloat() ?: sd.shakeSensitivity,
                nodSensitivity = s?.optDouble("nodSensitivity", sd.nodSensitivity.toDouble())?.toFloat() ?: sd.nodSensitivity,
                mouthOpenMar = s?.optDouble("mouthOpenMar", sd.mouthOpenMar.toDouble())?.toFloat() ?: sd.mouthOpenMar,
                crosshairSpeedPx = s?.optDouble("crosshairSpeedPx", sd.crosshairSpeedPx.toDouble())?.toFloat() ?: sd.crosshairSpeedPx,
                crosshairShakeSensitivity = s?.optDouble("crosshairShakeSensitivity", sd.crosshairShakeSensitivity.toDouble())?.toFloat() ?: sd.crosshairShakeSensitivity,
                crosshairNodSensitivity = s?.optDouble("crosshairNodSensitivity", sd.crosshairNodSensitivity.toDouble())?.toFloat() ?: sd.crosshairNodSensitivity
            ),
            portraitMap = decodeMap(root.optJSONObject("portrait"), d.portraitMap),
            landscapeMap = decodeMap(root.optJSONObject("landscape"), d.landscapeMap),
            crosshairMap = decodeMap(root.optJSONObject("crosshair"), d.crosshairMap)
        )
    }

    private fun decodeMap(o: JSONObject?, fallback: Map<FaceAction, GestureBehavior>): Map<FaceAction, GestureBehavior> {
        if (o == null) return fallback
        return FaceAction.entries.associateWith { action ->
            val name = o.optString(action.name, "")
            runCatching { GestureBehavior.valueOf(name) }
                .getOrDefault(fallback[action] ?: GestureBehavior.NONE)
        }
    }
}
