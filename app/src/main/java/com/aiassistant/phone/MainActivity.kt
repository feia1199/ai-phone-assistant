package com.aiassistant.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.aiassistant.phone.service.AIAccessibilityService
import com.aiassistant.phone.service.FloatWindowService
import com.aiassistant.phone.voice.VoiceListener
import com.aiassistant.phone.voice.VoiceSpeaker
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private lateinit var tvStatus: android.widget.TextView
    private lateinit var tvFloatStatus: android.widget.TextView
    private lateinit var tvLog: android.widget.TextView
    private lateinit var tvVoiceStatus: android.widget.TextView
    private lateinit var etCommand: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etApiUrl: TextInputEditText
    private lateinit var btnExecute: MaterialButton
    private lateinit var btnVoice: MaterialButton
    private lateinit var btnFloat: MaterialButton

    private var voiceListener: VoiceListener? = null
    private var voiceSpeaker: VoiceSpeaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvFloatStatus = findViewById(R.id.tvFloatStatus)
        tvLog = findViewById(R.id.tvLog)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        etCommand = findViewById(R.id.etCommand)
        etApiKey = findViewById(R.id.etApiKey)
        etApiUrl = findViewById(R.id.etApiUrl)
        btnExecute = findViewById(R.id.btnExecute)
        btnVoice = findViewById(R.id.btnVoice)
        btnFloat = findViewById(R.id.btnFloat)

        val btnOpenSettings = findViewById<MaterialButton>(R.id.btnOpenSettings)

        // 恢复配置
        val prefs = getSharedPreferences("ai_assistant", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etApiUrl.setText(prefs.getString("api_url", "https://api.openai.com/v1/chat/completions"))

        // 保存配置
        etApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.edit().putString("api_key", etApiKey.text.toString()).apply()
            }
        }
        etApiUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.edit().putString("api_url", etApiUrl.text.toString()).apply()
            }
        }

        // 开启无障碍权限
        btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 启动/停止悬浮窗
        btnFloat.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                // 请求悬浮窗权限
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 1001)
                return@setOnClickListener
            }
            if (FloatWindowService.isRunning) {
                stopService(Intent(this, FloatWindowService::class.java))
                btnFloat.text = "🫧 启动悬浮窗"
                addLog("⏹ 悬浮窗已停止")
            } else {
                startForegroundService(Intent(this, FloatWindowService::class.java))
                btnFloat.text = "🫧 停止悬浮窗"
                addLog("✅ 悬浮窗已启动，可在任意界面使用")
            }
        }

        // 初始化语音
        voiceListener = VoiceListener(this).apply {
            init()
            setOnResultListener { text ->
                runOnUiThread {
                    tvVoiceStatus.text = "✅ 识别到: $text"
                    etCommand.setText(text)
                    // 自动执行
                    executeCommand(text)
                }
            }
            setOnErrorListener { msg ->
                runOnUiThread {
                    tvVoiceStatus.text = "❌ $msg"
                    btnVoice.text = "🎤 语音输入"
                    voiceSpeaker?.speak(msg)
                }
            }
            setOnListeningListener {
                runOnUiThread {
                    tvVoiceStatus.text = "🔴 正在聆听..."
                }
            }
        }
        voiceSpeaker = VoiceSpeaker(this)

        // 语音输入按钮
        btnVoice.setOnClickListener {
            if (btnVoice.text.toString().contains("停止")) {
                voiceListener?.stopListening()
                btnVoice.text = "🎤 语音输入"
                tvVoiceStatus.text = ""
                return@setOnClickListener
            }
            btnVoice.text = "⏹ 停止录音"
            tvVoiceStatus.text = "🎤 请开始说话..."
            voiceSpeaker?.speak("请说指令")
            Thread.sleep(800)
            voiceListener?.startListening()
        }

        // 执行指令
        btnExecute.setOnClickListener {
            val command = etCommand.text.toString().trim()
            if (command.isEmpty()) {
                Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            executeCommand(command)
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (Settings.canDrawOverlays(this)) {
                addLog("✅ 悬浮窗权限已获取")
            } else {
                addLog("❌ 悬浮窗权限被拒绝")
            }
        }
    }

    private fun refreshStatus() {
        // 无障碍服务状态
        val isA11yRunning = isAccessibilityServiceRunning()
        tvStatus.text = if (isA11yRunning) "✅ 无障碍服务运行中" else "⏹ 请开启无障碍权限"
        tvStatus.setTextColor(getColor(if (isA11yRunning) android.R.color.holo_green_dark else android.R.color.holo_red_dark))

        // 悬浮窗状态
        tvFloatStatus.text = if (FloatWindowService.isRunning) "✅ 悬浮窗运行中" else "⏹ 悬浮窗未启动"
    }

    private fun isAccessibilityServiceRunning(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityManager.FEEDBACK_ALL_MASK)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun executeCommand(command: String) {
        addLog("📝 指令: $command")
        addLog("⏳ 正在分析屏幕并执行...")

        lifecycleScope.launch {
            try {
                val service = AIAccessibilityService.instance
                if (service == null) {
                    addLog("❌ 无障碍服务未启动")
                    voiceSpeaker?.speak("请先开启无障碍服务")
                    return@launch
                }

                val apiKey = etApiKey.text.toString().trim()
                val apiUrl = etApiUrl.text.toString().trim()

                if (apiKey.isEmpty()) {
                    addLog("❌ 请先配置 API Key")
                    voiceSpeaker?.speak("请先配置API密钥")
                    return@launch
                }

                // 1. 获取屏幕
                val screenInfo = withContext(Dispatchers.IO) {
                    service.captureScreenInfo()
                }
                addLog("📱 屏幕: ${screenInfo.take(150)}...")

                // 2. AI 分析
                addLog("🤖 AI 分析中...")
                val aiResponse = withContext(Dispatchers.IO) {
                    AIAnalyzer.analyzeAndPlan(apiKey, apiUrl, command, screenInfo)
                }
                addLog("🤖 AI: $aiResponse")

                // 3. 执行
                val result = withContext(Dispatchers.Main) {
                    service.executeAIPlan(aiResponse)
                }
                addLog(result)
                voiceSpeaker?.speak(if (result.startsWith("✅")) "执行完成" else result)

            } catch (e: Exception) {
                addLog("❌ 错误: ${e.message}")
                voiceSpeaker?.speak("执行失败")
            }
        }
    }

    private fun addLog(text: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        tvLog.append("\n[$timestamp] $text")

        // 限制日志长度
        val log = tvLog.text.toString()
        if (log.length > 5000) {
            tvLog.text = log.takeLast(3000)
        }

        // 滚动到底部
        val scrollView = tvLog.parent as android.widget.ScrollView
        scrollView.post { scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceListener?.destroy()
        voiceSpeaker?.destroy()
    }
}
