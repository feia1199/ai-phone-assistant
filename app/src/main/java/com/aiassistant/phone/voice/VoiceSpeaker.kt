package com.aiassistant.phone.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 语音播报 - 说
 * 使用安卓原生 TTS 引擎
 */
class VoiceSpeaker(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            // 设置中文语音
            val result = tts?.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 回退到英文
                tts?.setLanguage(Locale.US)
            }
            tts?.setSpeechRate(1.1f)
            tts?.setPitch(1.0f)

            // 如果有等待播报的文字，现在播报
            pendingText?.let {
                pendingText = null
                speak(it)
            }
        }
    }

    /**
     * 朗读文字
     */
    fun speak(text: String) {
        if (!isReady) {
            pendingText = text
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_assistant")
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

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
