package com.example.lianxu.data.api

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChatCompletionRequest(
    val model: String = "deepseek-flash",
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val tool_choice: String? = "auto",
    val thinking: Map<String, String> = mapOf("type" to "disabled"),
    val max_tokens: Int = 8192
)

@JsonClass(generateAdapter = true)
data class Message(
    val role: String,
    val content: Any? = null,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
    val reasoning_content: String? = null
)

@JsonClass(generateAdapter = true)
data class Tool(
    val type: String = "function",
    val function: FunctionDefinition
)

@JsonClass(generateAdapter = true)
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class ChatCompletionResponse(
    val id: String,
    val choices: List<Choice>
)

@JsonClass(generateAdapter = true)
data class Choice(
    val message: ResponseMessage,
    val finish_reason: String
)

@JsonClass(generateAdapter = true)
data class ResponseMessage(
    val role: String,
    val content: String?,
    val tool_calls: List<ToolCall>?,
    val reasoning_content: String? = null
)

@JsonClass(generateAdapter = true)
data class ToolCall(
    val id: String,
    val type: String,
    val function: ToolCallFunction
)

@JsonClass(generateAdapter = true)
data class ToolCallFunction(
    val name: String,
    val arguments: String
)
