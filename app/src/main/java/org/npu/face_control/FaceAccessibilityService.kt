package org.npu.face_control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 命中检测结果：点击/长按实际命中目标矩形 */
data class HitTestResult(
    val clickTarget: Rect?,
    val longClickTarget: Rect?
)

class FaceAccessibilityService : AccessibilityService() {

    private var currentStroke: GestureDescription.StrokeDescription? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPressing = false

    /** 双击两次点击之间的间隔（毫秒）：需大于系统合并不到的最小间隔，且小于应用双击判定阈值 */
    private val DOUBLE_CLICK_GAP_MS = 70L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun performClickAction(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 智能点击：优先对「光标正下方」的最小可点击元素执行 ACTION_CLICK；
     * 若该元素不可点击则点击其边界中心，再不行就精确坐标点击。
     * 关键：以「包含光标的最小节点」为准，而不是按半径找最近节点，避免吸附到相邻按钮。
     */
    fun performSmartClick(x: Float, y: Float) {
        val root = rootInActiveWindow
        if (root == null) {
            performClickAction(x, y)
            return
        }
        val leaf = findSmallestContaining(root, x, y)
        if (leaf == null) {
            performClickAction(x, y)
            return
        }
        val clickable = findActionableSelfOrAncestor(leaf) { it.isClickable }
        if (clickable != null) {
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clickable !== leaf) leaf.recycle()
            clickable.recycle()
            return
        }
        val rect = Rect()
        leaf.getBoundsInScreen(rect)
        leaf.recycle()
        performClickAction(rect.exactCenterX(), rect.exactCenterY())
    }

    /**
     * 智能双击：解析光标正下方最小可点击元素的中心，连发两次真实点击手势（间隔 DOUBLE_CLICK_GAP_MS）。
     * 相比连续两次 ACTION_CLICK，真实点击序列既能触发两次 onClick，也能被 GestureDetector.onDoubleTap 识别。
     */
    fun performSmartDoubleClick(x: Float, y: Float) {
        val root = rootInActiveWindow
        var tx = x
        var ty = y
        if (root != null) {
            val leaf = findSmallestContaining(root, x, y)
            if (leaf != null) {
                val clickable = findActionableSelfOrAncestor(leaf) { it.isClickable }
                val rect = Rect()
                if (clickable != null) {
                    clickable.getBoundsInScreen(rect)
                    clickable.recycle()
                } else {
                    leaf.getBoundsInScreen(rect)
                }
                leaf.recycle()
                if (rect.width() > 0 && rect.height() > 0) {
                    tx = rect.exactCenterX()
                    ty = rect.exactCenterY()
                }
            }
        }
        performClickAction(tx, ty)
        handler.postDelayed({ performClickAction(tx, ty) }, DOUBLE_CLICK_GAP_MS)
    }

    /**
     * 智能长按：对「光标正下方」的可长按元素执行 ACTION_LONG_CLICK。
     * @return true 表示已通过节点动作执行；false 表示下方无可长按元素，调用方应回退到持续按压。
     */
    fun performSmartLongClick(x: Float, y: Float): Boolean {
        val root = rootInActiveWindow ?: return false
        val leaf = findSmallestContaining(root, x, y) ?: return false
        val longClickable = findActionableSelfOrAncestor(leaf) { it.isLongClickable }
        if (longClickable == null) {
            leaf.recycle()
            return false
        }
        val ok = longClickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        if (longClickable !== leaf) leaf.recycle()
        longClickable.recycle()
        return ok
    }

    /**
     * 返回 (x,y) 处应按压的目标点：优先取光标正下方最小节点的边界中心，否则原坐标。
     * 供持续按压回退时使用，让落点吸附到元素中心。
     */
    fun resolveTargetPoint(x: Float, y: Float): Pair<Float, Float> {
        val root = rootInActiveWindow ?: return x to y
        val leaf = findSmallestContaining(root, x, y) ?: return x to y
        val rect = Rect()
        leaf.getBoundsInScreen(rect)
        leaf.recycle()
        return rect.exactCenterX() to rect.exactCenterY()
    }

    /**
     * 命中检测：返回点击命中目标、长按命中目标，供光标模式高亮显示。
     * 只找「光标正下方」的最小节点及其可点击/可长按祖先，不做半径搜索。
     */
    fun hitTest(x: Float, y: Float): HitTestResult {
        val root = rootInActiveWindow
        if (root == null) return HitTestResult(null, null)
        val rect = Rect()
        val ix = x.toInt()
        val iy = y.toInt()

        // 用根节点 bounds 估算屏幕面积，用于过滤「覆盖大半个屏幕」的容器节点
        val rootRect = Rect()
        root.getBoundsInScreen(rootRect)
        val screenArea = (rootRect.width().toLong() * rootRect.height().toLong()).coerceAtLeast(1L)
        val maxInteractiveArea = screenArea * 3 / 10

        // 遍历：找包含点的最小节点
        var leaf: AccessibilityNodeInfo? = null
        var leafArea = Long.MAX_VALUE
        val stack = mutableListOf<AccessibilityNodeInfo>(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) {
                node.recycle()
                continue
            }
            if (rect.contains(ix, iy)) {
                val area = rect.width().toLong() * rect.height().toLong()
                if (area < leafArea) {
                    leaf?.recycle()
                    leaf = node
                    leafArea = area
                } else {
                    node.recycle()
                }
            } else {
                node.recycle()
            }
        }

        // 从 leaf 向上找可点击/可长按祖先（跳过超大节点）
        var clickTarget: Rect? = null
        var longClickTarget: Rect? = null
        var cur: AccessibilityNodeInfo? = leaf
        while (cur != null) {
            val curRect = Rect()
            cur.getBoundsInScreen(curRect)
            val area = curRect.width().toLong() * curRect.height().toLong()
            val smallEnough = area <= maxInteractiveArea
            if (clickTarget == null && cur.isClickable && smallEnough) {
                clickTarget = curRect
            }
            if (longClickTarget == null && cur.isLongClickable && smallEnough) {
                longClickTarget = curRect
            }
            val parent = cur.parent
            if (cur !== leaf) cur.recycle()
            cur = parent
        }
        leaf?.recycle()
        return HitTestResult(clickTarget, longClickTarget)
    }

    /** 遍历节点树，返回包含 (x,y) 的最小面积节点（最深/最具体的元素）。 */
    private fun findSmallestContaining(
        root: AccessibilityNodeInfo,
        x: Float,
        y: Float
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val rect = Rect()
        val ix = x.toInt()
        val iy = y.toInt()
        val stack = mutableListOf<AccessibilityNodeInfo>(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            val childCount = node.childCount
            for (i in 0 until childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) {
                node.recycle()
                continue
            }
            if (rect.contains(ix, iy)) {
                val area = rect.width().toLong() * rect.height().toLong()
                if (area < bestArea) {
                    best?.recycle()
                    best = node
                    bestArea = area
                } else {
                    node.recycle()
                }
            } else {
                node.recycle()
            }
        }
        return best
    }

    /**
     * 从 node 出发向上（含自身）找第一个满足 predicate 的节点。
     * 调用方负责回收返回的节点与传入的 node；中间节点在此回收。
     */
    private fun findActionableSelfOrAncestor(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (predicate(current)) {
                return current
            }
            val parent = current.parent
            if (current !== node) {
                current.recycle()
            }
            current = parent
        }
        return null
    }

    // 开始持续按压
    fun startContinuousPress(x: Float, y: Float) {
        if (isPressing) return
        isPressing = true
        
        val path = Path().apply { moveTo(x, y) }
        // 初始按压
        currentStroke = GestureDescription.StrokeDescription(path, 0, 200, true)
        val gesture = GestureDescription.Builder().addStroke(currentStroke!!).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (isPressing) {
                    continuePress(x, y)
                }
            }
        }, null)
    }

    // 续期按压
    private fun continuePress(x: Float, y: Float) {
        if (!isPressing) return
        
        val path = Path().apply { moveTo(x, y) }
        currentStroke = currentStroke?.continueStroke(path, 0, 200, true)
        currentStroke?.let {
            val gesture = GestureDescription.Builder().addStroke(it).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.postDelayed({
                        if (isPressing) continuePress(x, y)
                    }, 10)
                }
            }, null)
        }
    }

    // 停止按压
    fun stopContinuousPress(x: Float, y: Float) {
        isPressing = false
        currentStroke?.let {
            val path = Path().apply { moveTo(x, y) }
            val lastStroke = it.continueStroke(path, 0, 100, false)
            val gesture = GestureDescription.Builder().addStroke(lastStroke).build()
            dispatchGesture(gesture, null, null)
            currentStroke = null
        }
    }

    fun performSwipeAction(startX: Float, startY: Float, endX: Float, endY: Float) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        var instance: FaceAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}