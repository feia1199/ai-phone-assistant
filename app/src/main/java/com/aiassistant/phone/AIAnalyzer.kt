package com.aiassistant.phone

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI 分析器
 * 将屏幕内容发送给 AI，获取操作计划
 */
object AIAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 系统提示词 - 告诉 AI 它的角色和能力
     */
    private const val SYSTEM_PROMPT = """你是一个手机自动化 AI 助手。你通过无障碍服务读取手机屏幕上的界面元素，然后生成操作指令来帮用户完成任务。

你可以执行以下操作：
1. click:"目标文本" - 点击屏幕上包含该文本的元素（如按钮、链接、菜单项）
2. input:"目标输入框"="输入内容" - 在指定输入框中输入文字
3. scroll:up 或 scroll:down - 向上或向下滚动屏幕
4. back - 按返回键
5. home - 回到桌面
6. open_app:"应用名" - 打开指定应用（如 微信、支付宝、淘宝）
7. wait:毫秒数 - 等待指定毫秒数

规则：
- 每行一个操作指令
- 用 // 或 # 开头写注释
- 先分析当前屏幕状态，再给出操作步骤
- 如果需要输入框中没有的内容，优先用 find 匹配
- 只输出操作指令，不要输出其他解释
- 操作要简洁高效，尽量少步骤完成

示例：
用户说"打开微信"
输出：
open_app:"微信"
wait:2000

用户说"在搜索框输入Hello"
输出：
click:"搜索"
wait:500
input:"搜索"="Hello"
"""

    /**
     * 分析屏幕内容并生成操作计划
     */
    fun analyzeAndPlan(
        apiKey: String,
        apiUrl: String,
        userCommand: String,
        screenInfo: String
    ): String {
        val requestBody = ChatRequest(
            model = "gpt-4o",
            messages = listOf(
                Message(role = "system", content = SYSTEM_PROMPT),
                Message(
                    role = "user",
                    content = """当前屏幕内容：
$screenInfo

用户指令：$userCommand

请生成操作指令："""
                )
            ),
            maxTokens = 1024,
            temperature = 0.1
        )

        val json = gson.toJson(requestBody)
        val body = json.toRequestBody(JSON)

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("AI 无响应")

        if (!response.isSuccessful) {
            throw Exception("AI API 错误 (${response.code}): $responseBody")
        }

        val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
        return chatResponse.choices.firstOrNull()?.message?.content
            ?: throw Exception("AI 返回为空")
    }

    // --- 数据类 ---

    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        @SerializedName("max_tokens") val maxTokens: Int,
        val temperature: Double
    )

    data class Message(
        val role: String,
        val content: String
    )

    data class ChatResponse(
        val choices: List<Choice>
    )

    data class Choice(
        val message: Message
    )
}
