package org.npu.face_control.config

/**
 * 一个 FaceAction 被识别后要执行的行为（与屏幕朝向无关的语义化手势）。
 * - [label] 用于设置页下拉显示
 * - 枚举名（name）用于 JSON 持久化，稳定不变
 */
enum class GestureBehavior(val label: String) {
    NONE("无动作"),
    SWIPE_UP("上滑"),
    SWIPE_DOWN("下滑"),
    SWIPE_LEFT("左滑"),
    SWIPE_RIGHT("右滑"),
    CLICK("单击"),
    DOUBLE_CLICK("双击"),
    LONG_CLICK("长按"),
    PRESS_START("持续按压开始"),
    PRESS_END("持续按压结束"),
    TOGGLE_CROSSHAIR("进入/退出准心模式");

    companion object {
        val ALL: List<GestureBehavior> = entries.toList()
    }
}
