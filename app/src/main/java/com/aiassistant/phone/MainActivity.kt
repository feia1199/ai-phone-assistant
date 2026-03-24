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
import com.aiassistant.phone.voice.VoiceSpeakerManager
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
    private lateinit var tvCurrentVoice: android.widget.TextView
    private lateinit var etCommand: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etApiUrl: TextInputEditText
    private lateinit var btnExecute: MaterialButton
    private lateinit var btnVoice: MaterialButton
    private lateinit var btnFloat: MaterialButton

    private var voiceListener: VoiceListener? = null
    private var voiceSpeaker: VoiceSpeakerManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initVoiceSystem()
        loadSettings()
        setupListeners()
        refreshStatus()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvFloatStatus = findViewById(R.id.tvFloatStatus)
        tvLog = findViewById(R.id.tvLog)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        tvCurrentVoice = findViewById(R.id.tvCurrentVoice)
        etCommand = findViewById(R.id.etCommand)
        etApiKey = findViewById(R.id.etApiKey)
        etApiUrl = findViewById(R.id.etApiUrl)
        btnExecute = findViewById(R.id.btnExecute)
        btnVoice = findViewById(R.id.btnVoice)
        btnFloat = findViewById(R.id.btnFloat)
    }

    private fun initVoiceSystem() {
        // 初始化语音管理器（默认豆包风格）
        voiceSpeaker = VoiceSpeakerManager(this).apply {
            setOnInitListener { success ->
                if (success) {
                    // 初始化成功，播放欢迎语
                    doubaoGreeting()
                }
            }
        }

        // 初始化语音识别
        voiceListener = VoiceListener(this).apply {
            init()
            setOnResultListener { text ->
                runOnUiThread {
                    tvVoiceStatus.text = "✅ 识别到: $text"
                    etCommand.setText(text)
                    voiceSpeaker?.doubaoFeedback(true)
                    executeCommand(text)
                }
            }
            setOnErrorListener { msg ->
                runOnUiThread {
                    tvVoiceStatus.text = "❌ $msg"
                    btnVoice.text = "🎤 语音输入"
                    voiceSpeaker?.speak(msg, VoiceSpeakerManager.VoiceStyle.DOUBAO)
                }
            }
            setOnListeningListener {
                runOnUiThread {
                    tvVoiceStatus.text = "🔴 正在聆听..."
                }
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("ai_assistant", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etApiUrl.setText(prefs.getString("api_url", "https://api.openai.com/v1/chat/completions"))
        
        // 加载语音风格设置
        val voiceStyleName = prefs.getString("voice_style", "DOUBAO")
        val voiceStyle = VoiceSpeakerManager.VoiceStyle.valueOf(voiceStyleName ?: "DOUBAO")
        voiceSpeaker?.setVoiceStyle(voiceStyle)
        updateVoiceStyleUI(voiceStyle)
    }

    private fun setupListeners() {
        val prefs = getSharedPreferences("ai_assistant", MODE_PRIVATE)

        // 保存 API Key
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
        findViewById<MaterialButton>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 启动/停止悬浮窗
        btnFloat.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 1001)
                return@setOnClickListener
            }
            toggleFloatWindow()
        }

        // 语音风格选择
        findViewById<MaterialButton>(R.id.btnVoiceDoubao).setOnClickListener {
            setVoiceStyle(VoiceSpeakerManager.VoiceStyle.DOUBAO)
        }
        findViewById<MaterialButton>(R.id.btnVoiceXiaoyan).setOnClickListener {
            setVoiceStyle(VoiceSpeakerManager.VoiceStyle.XIAOYAN)
        }
        findViewById<MaterialButton>(R.id.btnVoiceXiaofeng).setOnClickListener {
            setVoiceStyle(VoiceSpeakerManager.VoiceStyle.XIAOFENG)
        }

        // 试听按钮
        findViewById<MaterialButton>(R.id.btnTestVoice).setOnClickListener {
            voiceSpeaker?.testVoice()
        }

        // 语音输入
        btnVoice.setOnClickListener {
            if (btnVoice.text.toString().contains("停止")) {
                stopVoiceInput()
            } else {
                startVoiceInput()
            }
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
    }

    private fun setVoiceStyle(style: VoiceSpeakerManager.VoiceStyle) {
        voiceSpeaker?.setVoiceStyle(style)
        getSharedPreferences("ai_assistant", MODE_PRIVATE)
            .edit()
            .putString("voice_style", style.name)
            .apply()
        updateVoiceStyleUI(style)
        
        // 播放示例
        voiceSpeaker?.testVoice(style)
    }

    private fun updateVoiceStyleUI(style: VoiceSpeakerManager.VoiceStyle) {
        val speaker = voiceSpeaker ?: return
        tvCurrentVoice.text = "当前：${speaker.getStyleName(style)} - ${speaker.getStyleDescription(style)}"
    }

    private fun toggleFloatWindow() {
        if (FloatWindowService.isRunning) {
            stopService(Intent(this, FloatWindowService::class.java))
            btnFloat.text = "🫧 启动悬浮窗"
            addLog("⏹ 悬浮窗已停止")
        } else {
            startForegroundService(Intent(this, FloatWindowService::class.java))
            btnFloat.text = "🫧 停止悬浮窗"
            addLog("✅ 悬浮窗已启动，可在任意界面使用")
            voiceSpeaker?.speak("悬浮窗已启动", VoiceSpeakerManager.VoiceStyle.DOUBAO)
        }
    }

    private fun startVoiceInput() {
        btnVoice.text = "⏹ 停止录音"
        tvVoiceStatus.text = "🎤 请开始说话..."
        voiceSpeaker?.speak("请说指令", VoiceSpeakerManager.VoiceStyle.DOUBAO)
        Thread.sleep(800)
        voiceListener?.startListening()
    }

    private fun stopVoiceInput() {
        voiceListener?.stopListening()
        btnVoice.text = "🎤 语音输入"
        tvVoiceStatus.text = ""
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
                toggleFloatWindow()
            } else {
                addLog("❌ 悬浮窗权限被拒绝")
            }
        }
    }

    private fun refreshStatus() {
        val isA11yRunning = isAccessibilityServiceRunning()
        tvStatus.text = if (isA11yRunning) "✅ 无障碍服务运行中" else "⏹ 请开启无障碍权限"
        tvStatus.setTextColor(getColor(if (isA11yRunning) android.R.color.holo_green_dark else android.R.color.holo_red_dark))

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
                    voiceSpeaker?.speak("请先开启无障碍服务", VoiceSpeakerManager.VoiceStyle.DOUBAO)
                    return@launch
                }

                val apiKey = etApiKey.text.toString().trim()
                val apiUrl = etApiUrl.text.toString().trim()

                if (apiKey.isEmpty()) {
                    addLog("❌ 请先配置 API Key")
                    voiceSpeaker?.speak("请先配置API密钥", VoiceSpeakerManager.VoiceStyle.DOUBAO)
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
                
                // 语音反馈
                if (result.startsWith("✅")) {
                    voiceSpeaker?.doubaoFeedback(true)
                } else {
                    voiceSpeaker?.doubaoFeedback(false, result)
                }

            } catch (e: Exception) {
                addLog("❌ 错误: ${e.message}")
                voiceSpeaker?.doubaoFeedback(false, "执行失败")
            }
        }
    }

    private fun addLog(text: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        tvLog.append("\n[$timestamp] $text")

        val log = tvLog.text.toString()
        if (log.length > 5000) {
            tvLog.text = log.takeLast(3000)
        }

        val scrollView = tvLog.parent as android.widget.ScrollView
        scrollView.post { scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceListener?.destroy()
        voiceSpeaker?.destroy()
    }
}
