package com.example.lianxu.data.api

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChatCompletionRequest(
    val model: String = "deepseek-flash",
    val messages: List<Message>,
    val tools: List<Tool>? = null,
    val tool_choice: String? = "auto"
)

@JsonClass(generateAdapter = true)
data class Message(
    val role: String,
    val content: String
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
    val parameters: Parameters
)

@JsonClass(generateAdapter = true)
data class Parameters(
    val type: String = "object",
    val properties: Map<String, PropertyDefinition>,
    val required: List<String>
)

@JsonClass(generateAdapter = true)
data class PropertyDefinition(
    val type: String,
    val description: String,
    val items: ItemDefinition? = null
)

@JsonClass(generateAdapter = true)
data class ItemDefinition(
    val type: String,
    val properties: Map<String, PropertyDefinition>? = null,
    val required: List<String>? = null
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
    val tool_calls: List<ToolCall>?
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
