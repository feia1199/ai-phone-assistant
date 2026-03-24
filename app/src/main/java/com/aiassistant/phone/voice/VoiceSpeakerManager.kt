package com.aiassistant.phone.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * 语音播报管理器 - 支持多种声音风格
 * 
 * 支持的声音风格：
 * - DEFAULT: 系统默认
 * - DOUBAO: 豆包风格（年轻女性，活泼亲切）
 * - XIAOYAN: 小燕风格（温柔女声）
 * - XIAOFENG: 小风风格（成熟男声）
 */
class VoiceSpeakerManager(private val context: Context) {

    enum class VoiceStyle {
        DEFAULT,    // 系统默认
        DOUBAO,     // 豆包风格
        XIAOYAN,    // 温柔女声
        XIAOFENG,   // 成熟男声
        CUTE        // 可爱童声
    }

    private var tts: TextToSpeech? = null
    private var currentStyle: VoiceStyle = VoiceStyle.DOUBAO
    private var isReady = false
    private var pendingText: String? = null
    private var onInitCallback: ((Boolean) -> Unit)? = null

    // 豆包风格的语音参数
    private val doubaoParams = VoiceParams(
        pitch = 1.15f,      // 音调略高，更年轻
        rate = 1.05f,       // 语速稍快，更活泼
        volume = 1.0f       // 音量正常
    )

    // 其他风格参数
    private val styleParams = mapOf(
        VoiceStyle.DEFAULT to VoiceParams(1.0f, 1.0f, 1.0f),
        VoiceStyle.DOUBAO to VoiceParams(1.15f, 1.05f, 1.0f),
        VoiceStyle.XIAOYAN to VoiceParams(1.05f, 0.95f, 0.9f),
        VoiceStyle.XIAOFENG to VoiceParams(0.9f, 0.9f, 1.0f),
        VoiceStyle.CUTE to VoiceParams(1.25f, 1.1f, 1.0f)
    )

    data class VoiceParams(
        val pitch: Float,
        val rate: Float,
        val volume: Float
    )

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                applyVoiceStyle(currentStyle)
                onInitCallback?.invoke(true)
                
                // 播放等待中的文字
                pendingText?.let {
                    pendingText = null
                    speak(it)
                }
            } else {
                onInitCallback?.invoke(false)
            }
        }
    }

    /**
     * 设置语音风格
     */
    fun setVoiceStyle(style: VoiceStyle) {
        currentStyle = style
        if (isReady) {
            applyVoiceStyle(style)
        }
    }

    /**
     * 获取当前语音风格
     */
    fun getCurrentStyle(): VoiceStyle = currentStyle

    /**
     * 获取所有可用的语音风格
     */
    fun getAvailableStyles(): List<VoiceStyle> = VoiceStyle.values().toList()

    /**
     * 获取风格的中文名称
     */
    fun getStyleName(style: VoiceStyle): String = when (style) {
        VoiceStyle.DEFAULT -> "系统默认"
        VoiceStyle.DOUBAO -> "🎀 豆包"
        VoiceStyle.XIAOYAN -> "🌸 小燕"
        VoiceStyle.XIAOFENG -> "🎩 小风"
        VoiceStyle.CUTE -> "🧸 可爱"
    }

    /**
     * 获取风格的描述
     */
    fun getStyleDescription(style: VoiceStyle): String = when (style) {
        VoiceStyle.DEFAULT -> "使用系统默认语音"
        VoiceStyle.DOUBAO -> "年轻女性，活泼亲切"
        VoiceStyle.XIAOYAN -> "温柔女声，优雅舒缓"
        VoiceStyle.XIAOFENG -> "成熟男声，稳重可靠"
        VoiceStyle.CUTE -> "可爱童声，俏皮活泼"
    }

    private fun applyVoiceStyle(style: VoiceStyle) {
        val params = styleParams[style] ?: styleParams[VoiceStyle.DEFAULT]!!
        
        tts?.apply {
            // 设置中文
            val result = setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                setLanguage(Locale.SIMPLIFIED_CHINESE)
            }
            
            // 应用参数
            setPitch(params.pitch)
            setSpeechRate(params.rate)
            
            // 尝试设置特定声音（如果设备支持）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setPreferredVoice(style)
            }
        }
    }

    private fun setPreferredVoice(style: VoiceStyle) {
        try {
            val voices = tts?.voices
            if (voices != null) {
                // 根据风格选择最佳声音
                val preferredVoice = when (style) {
                    VoiceStyle.DOUBAO -> voices.find { 
                        it.name.contains("female", true) && 
                        (it.name.contains("zh", true) || it.locale.language == "zh")
                    }
                    VoiceStyle.XIAOYAN -> voices.find { 
                        it.name.contains("female", true) 
                    }
                    VoiceStyle.XIAOFENG -> voices.find { 
                        it.name.contains("male", true) 
                    }
                    VoiceStyle.CUTE -> voices.minByOrNull { it.name.length }
                    else -> null
                }
                
                preferredVoice?.let { 
                    tts?.voice = it
                }
            }
        } catch (e: Exception) {
            // 忽略声音设置错误，使用默认
        }
    }

    /**
     * 朗读文字
     */
    fun speak(text: String, style: VoiceStyle? = null) {
        val targetStyle = style ?: currentStyle
        
        if (!isReady) {
            pendingText = text
            // 如果指定了风格，临时切换
            style?.let { currentStyle = it }
            return
        }

        // 如果指定了不同风格，临时切换
        if (style != null && style != currentStyle) {
            applyVoiceStyle(style)
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_assistant_${System.currentTimeMillis()}")

        // 恢复默认风格
        if (style != null && style != currentStyle) {
            applyVoiceStyle(currentStyle)
        }
    }

    /**
     * 停止朗读
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * 是否正在朗读
     */
    val isSpeaking: Boolean
        get() = tts?.isSpeaking == true

    /**
     * 测试当前语音风格
     */
    fun testVoice(style: VoiceStyle? = null) {
        val testText = when (style ?: currentStyle) {
            VoiceStyle.DOUBAO -> "你好呀！我是你的 AI 助手，有什么可以帮你的吗？"
            VoiceStyle.XIAOYAN -> "您好，很高兴为您服务。"
            VoiceStyle.XIAOFENG -> "你好，我是你的智能助手。"
            VoiceStyle.CUTE -> "嗨嗨！我来啦！"
            else -> "语音测试"
        }
        speak(testText, style)
    }

    /**
     * 豆包风格的打招呼
     */
    fun doubaoGreeting() {
        val greetings = listOf(
            "你好呀！我是你的 AI 助手，有什么可以帮你的吗？",
            "嗨！我在这里呢，需要我做什么？",
            "哈喽！准备好开始了吗？",
            "你好！今天想让我帮你做什么？"
        )
        speak(greetings.random(), VoiceStyle.DOUBAO)
    }

    /**
     * 豆包风格的反馈
     */
    fun doubaoFeedback(success: Boolean, message: String = "") {
        when {
            success -> {
                val responses = listOf(
                    "搞定啦！",
                    "完成！",
                    "好啦！",
                    "搞定！"
                )
                speak(responses.random(), VoiceStyle.DOUBAO)
            }
            message.isNotEmpty() -> speak(message, VoiceStyle.DOUBAO)
            else -> speak("哎呀，好像出了点问题", VoiceStyle.DOUBAO)
        }
    }

    fun setOnInitListener(callback: (Boolean) -> Unit) {
        onInitCallback = callback
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

import android.os.Build
