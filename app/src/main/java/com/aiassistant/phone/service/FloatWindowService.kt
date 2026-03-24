package com.aiassistant.phone.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aiassistant.phone.MainActivity
import com.aiassistant.phone.R
import com.aiassistant.phone.voice.VoiceListener
import com.aiassistant.phone.voice.VoiceSpeaker
import kotlinx.coroutines.*

/**
 * 悬浮窗服务
 * 在任意 App 上方显示控制面板，支持语音下达指令
 */
class FloatWindowService : Service() {

    companion object {
        private const val CHANNEL_ID = "ai_assistant_float"
        private const val NOTIFICATION_ID = 1001
        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isExpanded = false
    private var isRecording = false

    private var voiceListener: VoiceListener? = null
    private var voiceSpeaker: VoiceSpeaker? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 拖动相关
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isRunning = true

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 初始化语音模块
        voiceListener = VoiceListener(this).apply {
            init()
            setOnResultListener { text ->
                onVoiceResult(text)
            }
            setOnErrorListener { msg ->
                onVoiceError(msg)
            }
            setOnListeningListener {
                onListeningStart()
            }
        }
        voiceSpeaker = VoiceSpeaker(this)

        showFloatWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        floatView?.let { windowManager.removeView(it) }
        voiceListener?.destroy()
        voiceSpeaker?.destroy()
        scope.cancel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        floatView = LayoutInflater.from(this).inflate(R.layout.float_panel, null)

        val ballContainer = floatView!!.findViewById<LinearLayout>(R.id.ballContainer)
        val expandPanel = floatView!!.findViewById<LinearLayout>(R.id.expandPanel)
        val tvListeningStatus = floatView!!.findViewById<TextView>(R.id.tvListeningStatus)
        val recordingDot = floatView!!.findViewById<View>(R.id.recordingDot)

        val btnRecord = floatView!!.findViewById<View>(R.id.btnRecord)
        val btnHome = floatView!!.findViewById<View>(R.id.btnHome)
        val btnBack = floatView!!.findViewById<View>(R.id.btnBack)
        val btnClose = floatView!!.findViewById<View>(R.id.btnClose)

        // 悬浮球点击 / 拖动
        ballContainer.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isDragging = true
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(floatView, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // 单击 → 展开/收起面板
                            isExpanded = !isExpanded
                            expandPanel.visibility = if (isExpanded) View.VISIBLE else View.GONE

                            // 展开时获取焦点以便接收点击
                            if (isExpanded) {
                                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            } else {
                                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            }
                            windowManager.updateViewLayout(floatView, params)
                        }
                    }
                }
                return true
            }
        })

        // 录音按钮
        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        // 回桌面
        btnHome.setOnClickListener {
            val service = AIAccessibilityService.instance
            if (service != null) {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                voiceSpeaker?.speak("已回到桌面")
            } else {
                Toast.makeText(this, "无障碍服务未启动", Toast.LENGTH_SHORT).show()
            }
            collapsePanel()
        }

        // 返回
        btnBack.setOnClickListener {
            val service = AIAccessibilityService.instance
            if (service != null) {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
            collapsePanel()
        }

        // 关闭
        btnClose.setOnClickListener {
            collapsePanel()
        }

        windowManager.addView(floatView, params)
    }

    private fun startRecording() {
        isRecording = true

        // 更新 UI
        floatView?.let {
            it.findViewById<TextView>(R.id.tvListeningStatus).text = "🔴 正在聆听..."
            it.findViewById<View>(R.id.recordingDot).visibility = View.VISIBLE
        }

        voiceSpeaker?.speak("请说指令")
        // 等语音说完再开始识别
        Thread.sleep(800)
        voiceListener?.startListening()
    }

    private fun stopRecording() {
        isRecording = false
        voiceListener?.stopListening()

        floatView?.let {
            it.findViewById<TextView>(R.id.tvListeningStatus).text = "🎤 点击录音"
            it.findViewById<View>(R.id.recordingDot).visibility = View.GONE
        }
    }

    private fun onListeningStart() {
        // 语音识别器就绪
    }

    private fun onVoiceResult(text: String) {
        stopRecording()
        voiceSpeaker?.speak("收到，正在执行：$text")

        // 调用 AI 执行
        scope.launch {
            try {
                val service = AIAccessibilityService.instance
                if (service == null) {
                    voiceSpeaker?.speak("请先启动无障碍服务")
                    return@launch
                }

                val prefs = getSharedPreferences("ai_assistant", Context.MODE_PRIVATE)
                val apiKey = prefs.getString("api_key", "") ?: ""
                val apiUrl = prefs.getString("api_url", "https://api.openai.com/v1/chat/completions") ?: ""

                if (apiKey.isEmpty()) {
                    voiceSpeaker?.speak("请先在主界面配置 API Key")
                    return@launch
                }

                // 1. 获取屏幕
                val screenInfo = withContext(Dispatchers.IO) {
                    service.captureScreenInfo()
                }

                // 2. AI 分析
                val aiPlan = withContext(Dispatchers.IO) {
                    com.aiassistant.phone.AIAnalyzer.analyzeAndPlan(apiKey, apiUrl, text, screenInfo)
                }

                // 3. 执行
                val result = withContext(Dispatchers.Main) {
                    service.executeAIPlan(aiPlan)
                }

                voiceSpeaker?.speak("执行完成")

            } catch (e: Exception) {
                voiceSpeaker?.speak("执行失败，${e.message}")
            }
        }

        collapsePanel()
    }

    private fun onVoiceError(msg: String) {
        stopRecording()
        voiceSpeaker?.speak(msg)
    }

    private fun collapsePanel() {
        isExpanded = false
        floatView?.let {
            it.findViewById<LinearLayout>(R.id.expandPanel).visibility = View.GONE
            val params = it.layoutParams as WindowManager.LayoutParams
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(it, params)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI 助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI 助手悬浮窗正在运行"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AI 助手")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pendingIntent)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AI 助手")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pendingIntent)
                .build()
        }
    }
}
