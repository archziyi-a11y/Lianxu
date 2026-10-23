package com.example.lianxu.data.api

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface DeepSeekApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authHeader: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}
