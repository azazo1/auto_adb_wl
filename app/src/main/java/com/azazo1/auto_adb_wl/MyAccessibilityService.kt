package com.azazo1.auto_adb_wl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MyAccessibilityService : AccessibilityService() {
    companion object {
        // 静态实例，用于在外部（Activity）调用服务功能
        var instance: MyAccessibilityService? = null
    }

    /// 用户同意了无障碍权限的时候
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this  // 保存服务实例
        // 如果需要动态修改配置信息，可以在此设置 serviceInfo；此处利用 XML 配置，无需额外设置
        // 见 <pj>/app/src/main/res/xml/accessibility_service_config.xml
        Log.i("mas", "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        event?.let {
//            Log.i("mas", log(it.eventType.toDouble(), 2.toDouble()).toString())
//        }
//        event?.source?.let {
//            Log.i("mas", it.toString())
//        }
//        Log.i("mas", "onAccessibilityEvent")
    }

    override fun onInterrupt() {
        Log.i("mas", "onInterrupt")
    }

    /// 用户决定关闭无障碍权限时
    override fun onUnbind(intent: Intent?): Boolean {
        Log.i("mas", "onUnbind")
        return super.onUnbind(intent)
    }

    /**
     * 打开开发者选项 -> 无线调试页面并复制调试地址到剪贴板的核心流程
     */
    suspend fun openWirelessDebugAndGetAddr(): String? {
        // 为避免阻塞UI线程，可在新线程中执行自动化操作
        mainLooper.run {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        return withContext(Dispatchers.Default) {

            delay(1000)  // 等待开发者选项页面加载

            // 3. 在开发者选项页面寻找“无线调试”并点击进入其设置页
            var wirelessDebugNode = findNodeByText("Wireless debugging", "无线调试")
            var i = 0
            while (wirelessDebugNode == null && i < 10) {
                performSwipeDown()
                wirelessDebugNode = findNodeByText("Wireless debugging", "无线调试")
                i++
            }
            if (wirelessDebugNode == null) {
                performBack()
                return@withContext null
            }
            clickNode(wirelessDebugNode)

            delay(300)  // 等待无线调试详情页面出现

            // 4. 从无线调试页面提取IP地址和端口号文本
            var debugAddress = findDebugAddressText()  // 尝试匹配形如 "192.168.x.x:port" 的文本
            if (debugAddress == null) {
                // 如果没有读取到监听的端口那么就关闭/开启无线调试继续.
                val debugTriggerBtn = findNodeByText("Wireless debugging", "无线调试")
                if (debugTriggerBtn == null) {
                    performBack()
                    performBack()
                    return@withContext null
                }
                clickNode(debugTriggerBtn)
                delay(300)
            }
            debugAddress = findDebugAddressText()  // 尝试匹配形如 "192.168.x.x:port" 的文本
            Log.i("mas", "find addr $debugAddress")
            performBack()
            performBack()
            return@withContext debugAddress
        }
    }

    /**
     * 辅助函数：根据给定文本查找当前界面节点（支持多语言匹配，返回第一个匹配项）
     */
    private fun findNodeByText(vararg texts: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (txt in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(txt)
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]  // 返回找到的第一个节点
            }
        }
        return null
    }

    /**
     * 辅助函数：根据完整View ID查找当前界面节点（需要FLAG_REPORT_VIEW_IDS生效）
     */
    private fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return if (!nodes.isNullOrEmpty()) nodes[0] else null
    }

    /**
     * 辅助函数：对指定节点执行点击动作（会自动寻找可点击的父节点）
     */
    private fun clickNode(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        // 寻找可点击的父节点
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * 辅助函数：遍历当前界面，提取形如 "IP:Port" 的调试地址文本
     */
    private fun findDebugAddressText(): String? {
        val root = rootInActiveWindow ?: return null
        // 查找包含 ":" 的所有节点，匹配其中的 IP:端口 模式
        val nodes = root.findAccessibilityNodeInfosByText(":")
        for (node in nodes) {
            val text = node.text?.toString() ?: continue
            val match = Regex("(\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+)").find(text)
            if (match != null) {
                return match.groupValues[1]  // 返回匹配的 "IP:端口" 子串
            }
        }
        return null
    }

    /**
     * 模拟向下滑动操作
     *
     * @param heightRatio 向下滑动的距离(占屏幕的百分比, 推荐取 0 ~ 0.7)
     * @param duration 滑动持续时间（毫秒）
     * @return true 如果手势成功发送并完成，false 如果手势被取消或失败。
     */
    suspend fun performSwipeDown(heightRatio: Float = 0.3f, duration: Long = 200L): Boolean {
        // 获取屏幕尺寸
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels // 使用 metricsHeightPixels 更准确

        // 定义滑动的起点和终点
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.7f // 屏幕高度的30%
        val endX = screenWidth / 2f
        val endY = screenHeight * (0.7f - heightRatio) // 屏幕高度的70%

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(strokeDescription)
        val gesture = gestureBuilder.build()

        // 使用 suspendCancellableCoroutine 来桥接异步回调到协程
        return suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    // 手势完成时恢复协程，返回true表示成功
                    if (continuation.isActive) { // 确保协程仍然活跃
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    // 手势被取消时恢复协程，返回false表示失败/取消
                    if (continuation.isActive) { // 确保协程仍然活跃
                        continuation.resume(false)
                    }
                }
            }

            // 调度手势
            val dispatched = dispatchGesture(gesture, callback, null)

            // 如果dispatchGesture返回false，表示手势未能被调度（例如，服务未启用）
            if (!dispatched) {
                if (continuation.isActive) {
                    continuation.resume(false) // 立即返回false
                }
            }

            // 设置取消逻辑：如果协程被取消，尝试取消手势（虽然dispatchGesture没有直接的取消API）
            continuation.invokeOnCancellation {
                // 在这里可以添加清理逻辑，例如如果dispatchGesture有取消方法
                // 但对于GestureDescription，一旦调度就无法直接通过API取消
                // 这里主要用于清理资源，或者在概念上表示操作被放弃
            }
        }
    }

    /**
     * 全局返回
     */
    suspend fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(100L)
    }
}