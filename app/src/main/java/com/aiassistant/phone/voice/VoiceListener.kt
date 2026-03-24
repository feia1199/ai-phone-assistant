package com.aiassistant.phone.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 语音识别 - 听
 * 使用安卓原生 SpeechRecognizer，离线可用
 */
class VoiceListener(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onListening: (() -> Unit)? = null

    fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("此设备不支持语音识别")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onListening?.invoke()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请重试"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时，请重试"
                        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        else -> "识别错误 ($error)"
                    }
                    onError?.invoke(msg)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotEmpty()) {
                        onResult?.invoke(text)
                    } else {
                        onError?.invoke("未识别到内容")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // 实时识别结果（可选展示）
                    val matches = partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    // 可在此更新 UI 显示中间结果
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    /**
     * 开始录音识别
     */
    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出指令...")
            // 支持更多语言
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_DEFAULT_LANGUAGE, true)
            // 设置最长录音时间
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            onError?.invoke("启动语音识别失败: ${e.message}")
        }
    }

    /**
     * 停止录音
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun setOnResultListener(listener: (String) -> Unit) {
        onResult = listener
    }

    fun setOnErrorListener(listener: (String) -> Unit) {
        onError = listener
    }

    fun setOnListeningListener(listener: () -> Unit) {
        onListening = listener
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
