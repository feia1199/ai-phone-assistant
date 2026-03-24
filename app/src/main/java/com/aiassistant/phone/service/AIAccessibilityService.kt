package com.aiassistant.phone

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AI 无障碍服务 - 核心组件
 * 负责读取屏幕内容、执行自动化操作
 */
class AIAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AIService"
        var instance: AIAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ AI 无障碍服务已启动")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 监听屏幕变化，可用于自动触发
    }

    override fun onInterrupt() {
        Log.d(TAG, "服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "❌ AI 无障碍服务已停止")
    }

    /**
     * 获取当前屏幕的文本信息（从无障碍树中提取）
     */
    fun captureScreenInfo(): String {
        val sb = StringBuilder()
        val root = rootInActiveWindow ?: return "无法获取屏幕内容"
        collectText(root, sb, 0)
        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 10) return

        val indent = "  ".repeat(depth)
        val text = node.text?.toString()
        val hint = node.hintText?.toString()
        val desc = node.contentDescription?.toString()
        val cls = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (!text.isNullOrEmpty() || !desc.isNullOrEmpty() || cls.contains("Button") || cls.contains("EditText")) {
            sb.appendLine("$indent[$cls] id=$viewId")
            if (!text.isNullOrEmpty()) sb.appendLine("$indent  text=\"$text\"")
            if (!hint.isNullOrEmpty()) sb.appendLine("$indent  hint=\"$hint\"")
            if (!desc.isNullOrEmpty()) sb.appendLine("$indent  desc=\"$desc\"")
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb, depth + 1)
        }
    }

    /**
     * 根据 AI 返回的操作计划执行动作
     * 支持的操作类型：
     *   - click: 点击文本匹配的元素
     *   - input: 在输入框中输入文字
     *   - scroll: 向指定方向滚动
     *   - back: 按返回键
     *   - home: 回到桌面
     *   - open_app: 打开指定 App
     */
    fun executeAIPlan(aiPlan: String): String {
        val lines = aiPlan.lines()
        val root = rootInActiveWindow ?: return "❌ 无法获取屏幕内容"

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) continue

            try {
                when {
                    trimmed.startsWith("click:", true) -> {
                        val target = trimmed.removePrefix("click:").trim().removeSurrounding("\"")
                        val found = findNodeByText(root, target)
                        if (found != null) {
                            found.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "✅ 点击: $target")
                            Thread.sleep(500)
                        } else {
                            return "❌ 未找到元素: $target"
                        }
                    }
                    trimmed.startsWith("input:", true) -> {
                        val parts = trimmed.removePrefix("input:").trim().split("=", limit = 2)
                        val target = parts.getOrNull(0)?.trim()?.removeSurrounding("\"") ?: continue
                        val value = parts.getOrNull(1)?.trim()?.removeSurrounding("\"") ?: continue
                        val found = findNodeByText(root, target)
                        if (found != null) {
                            val args = Bundle()
                            args.putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                value
                            )
                            found.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            Log.d(TAG, "✅ 输入 \"$value\" 到: $target")
                            Thread.sleep(300)
                        } else {
                            return "❌ 未找到输入框: $target"
                        }
                    }
                    trimmed.startsWith("scroll:", true) -> {
                        val direction = trimmed.removePrefix("scroll:").trim().lowercase()
                        performGlobalAction(
                            when (direction) {
                                "up" -> AccessibilityService.GLOBAL_ACTION_SCROLL_BACKWARD
                                "down" -> AccessibilityService.GLOBAL_ACTION_SCROLL_FORWARD
                                else -> AccessibilityService.GLOBAL_ACTION_SCROLL_FORWARD
                            }
                        )
                        Log.d(TAG, "✅ 滚动: $direction")
                        Thread.sleep(500)
                    }
                    trimmed.equals("back", true) -> {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        Log.d(TAG, "✅ 返回")
                        Thread.sleep(500)
                    }
                    trimmed.equals("home", true) -> {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        Log.d(TAG, "✅ 回到桌面")
                        Thread.sleep(500)
                    }
                    trimmed.startsWith("open_app:", true) -> {
                        val appName = trimmed.removePrefix("open_app:").trim().removeSurrounding("\"")
                        val result = openAppByName(appName)
                        if (result != null) return "❌ $result"
                        Log.d(TAG, "✅ 打开 App: $appName")
                        Thread.sleep(1000)
                    }
                    trimmed.startsWith("wait:", true) -> {
                        val ms = trimmed.removePrefix("wait:").trim().toLongOrNull() ?: 1000
                        Thread.sleep(ms)
                        Log.d(TAG, "⏳ 等待 ${ms}ms")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "执行失败: $trimmed", e)
                return "❌ 执行失败: $trimmed - ${e.message}"
            }
        }
        return "✅ 操作执行完成"
    }

    /**
     * 在无障碍树中查找文本匹配的节点
     */
    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // 精确匹配 text
        val node = findNodeByCondition(root) { node ->
            node.text?.toString() == text ||
            node.contentDescription?.toString() == text ||
            node.hintText?.toString() == text ||
            node.viewIdResourceName?.contains(text) == true
        }
        if (node != null) return node

        // 模糊匹配
        return findNodeByCondition(root) { node ->
            node.text?.toString()?.contains(text) == true ||
            node.contentDescription?.toString()?.contains(text) == true
        }
    }

    private fun findNodeByCondition(
        root: AccessibilityNodeInfo,
        condition: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (condition(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByCondition(child, condition)
            if (found != null) return found
        }
        return null
    }

    /**
     * 通过包名打开 App
     */
    private fun openAppByName(appName: String): String? {
        val packageMap = mapOf(
            "微信" to "com.tencent.mm",
            "支付宝" to "com.eg.android.AlipayGphone",
            "淘宝" to "com.taobao.taobao",
            "抖音" to "com.ss.android.ugc.aweme",
            "设置" to "com.android.settings",
            "相机" to "com.android.camera",
            "电话" to "com.android.dialer",
            "浏览器" to "com.android.browser",
            "QQ" to "com.tencent.mobileqq",
            "微博" to "com.sina.weibo",
            "百度" to "com.baidu.BaiduMap",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "B站" to "tv.danmaku.bili",
            "网易云音乐" to "com.netease.cloudmusic",
            "高德地图" to "com.autonavi.minimap"
        )

        val packageName = packageMap[appName]
        if (packageName == null) {
            // 尝试通过 launchIntent 查找
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage(appName)
            if (intent != null) {
                startActivity(intent)
                return null
            }
            return "未找到 App: $appName，支持的 App: ${packageMap.keys.joinToString("、")}"
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
            return null
        }
        return "App 未安装: $appName"
    }
}

import android.os.Bundle
