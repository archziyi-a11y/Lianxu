package com.example.lianxu.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lianxu.data.api.ChatCompletionRequest
import com.example.lianxu.data.api.DeepSeekApi
import com.example.lianxu.data.api.FunctionDefinition
import com.example.lianxu.data.api.ItemDefinition
import com.example.lianxu.data.api.Message
import com.example.lianxu.data.api.Parameters
import com.example.lianxu.data.api.PropertyDefinition
import com.example.lianxu.data.api.Tool
import com.example.lianxu.data.prefs.SettingsManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.UUID

class TodayViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsManager = SettingsManager(application)
    
    private val _cards = MutableStateFlow<List<DashboardCard>>(emptyList())
    val cards: StateFlow<List<DashboardCard>> = _cards.asStateFlow()
    
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.deepseek.com/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
                .build()
        )
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        
    private val api = retrofit.create(DeepSeekApi::class.java)

    init {
        _cards.value = listOf(
            DashboardCard(title = "Heart Rate", value = "72 bpm", span = CardSpan.SMALL),
            DashboardCard(title = "Calories", value = "320 kcal", span = CardSpan.SMALL),
            DashboardCard(title = "Steps", value = "4500", span = CardSpan.SMALL),
            DashboardCard(title = "Active Time", value = "45 min", span = CardSpan.MEDIUM),
            DashboardCard(title = "Distance", value = "3.2 km", span = CardSpan.MEDIUM),
            DashboardCard(title = "Daily Goal", value = "65% Complete", span = CardSpan.LARGE)
        )
    }

    fun toggleAnnotationMode(cardId: String) {
        _cards.value = _cards.value.map {
            if (it.id == cardId) {
                it.copy(isAnnotationMode = !it.isAnnotationMode)
            } else {
                it
            }
        }
    }

    fun updateAnnotationText(cardId: String, text: String) {
        _cards.value = _cards.value.map {
            if (it.id == cardId) {
                it.copy(annotationText = text)
            } else {
                it
            }
        }
    }

    fun updateAnnotationImage(cardId: String, uri: String) {
        _cards.value = _cards.value.map {
            if (it.id == cardId) {
                it.copy(annotationImageUri = uri)
            } else {
                it
            }
        }
    }
    
    fun getApiKey(callback: (String?) -> Unit) {
        viewModelScope.launch {
            callback(settingsManager.apiKeyFlow.firstOrNull())
        }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            settingsManager.saveApiKey(key)
        }
    }

    fun submitAnnotations() {
        val annotatedCards = _cards.value.filter { it.isAnnotationMode && (!it.annotationText.isNullOrEmpty() || !it.annotationImageUri.isNullOrEmpty()) }
        if (annotatedCards.isEmpty()) return

        _isProcessing.value = true

        viewModelScope.launch {
            val apiKey = settingsManager.apiKeyFlow.firstOrNull()
            
            if (apiKey.isNullOrEmpty()) {
                // Demo Mode
                delay(2000)
                
                // Simulate new cards
                val newCards = _cards.value.map { card ->
                    if (annotatedCards.any { it.id == card.id }) {
                        // Modify value slightly for demo
                        val newVal = when (card.title) {
                            "Heart Rate" -> "${(60..90).random()} bpm"
                            "Calories" -> "${(300..500).random()} kcal"
                            "Steps" -> "${(4000..6000).random()}"
                            "Active Time" -> "${(40..60).random()} min"
                            "Distance" -> "${String.format(java.util.Locale.US, "%.1f", (3..5).random() + Math.random())} km"
                            "Daily Goal" -> "${(70..90).random()}% Complete"
                            else -> card.value
                        }
                        card.copy(value = newVal, isAnnotationMode = false, annotationText = null, annotationImageUri = null)
                    } else {
                        card
                    }
                }
                _cards.value = newCards
                _isProcessing.value = false
            } else {
                // Real API Mode
                try {
                    val prompt = buildString {
                        append("Update the following fitness metrics based on user annotations:\\n")
                        annotatedCards.forEach { card ->
                            append("- ${card.title}: current value=${card.value}, user note='${card.annotationText}'\\n")
                        }
                    }

                    val request = ChatCompletionRequest(
                        messages = listOf(
                            Message(role = "system", content = "You are a fitness AI. Analyze the user's annotations on their fitness cards and return updated values. Output numeric values with units similar to the current format."),
                            Message(role = "user", content = prompt)
                        ),
                        tools = listOf(
                            Tool(
                                function = FunctionDefinition(
                                    name = "update_cards",
                                    description = "Updates the fitness dashboard cards",
                                    parameters = Parameters(
                                        properties = mapOf(
                                            "cards" to PropertyDefinition(
                                                type = "array",
                                                description = "List of updated cards",
                                                items = ItemDefinition(
                                                    type = "object",
                                                    properties = mapOf(
                                                        "title" to PropertyDefinition(type = "string", description = "Title of the card"),
                                                        "value" to PropertyDefinition(type = "string", description = "New updated value")
                                                    ),
                                                    required = listOf("title", "value")
                                                )
                                            )
                                        ),
                                        required = listOf("cards")
                                    )
                                )
                            )
                        )
                    )
                    
                    val response = api.createChatCompletion("Bearer $apiKey", request)
                    val toolCall = response.choices.firstOrNull()?.message?.tool_calls?.firstOrNull { it.function.name == "update_cards" }
                    
                    if (toolCall != null) {
                        // Parse toolCall.function.arguments (which is a JSON string of { "cards": [...] })
                        // In a real app we'd parse this with Moshi. For simplicity here, we'll try to find matching titles.
                        val argsMapAdapter = moshi.adapter(Map::class.java)
                        val map = argsMapAdapter.fromJson(toolCall.function.arguments)
                        val updatedCardsList = map?.get("cards") as? List<Map<String, String>>
                        
                        if (updatedCardsList != null) {
                            _cards.value = _cards.value.map { card ->
                                val updated = updatedCardsList.find { it["title"] == card.title }
                                if (updated != null) {
                                    card.copy(value = updated["value"] ?: card.value, isAnnotationMode = false, annotationText = null, annotationImageUri = null)
                                } else {
                                    card.copy(isAnnotationMode = false, annotationText = null, annotationImageUri = null)
                                }
                            }
                        } else {
                            _cards.value = _cards.value.map { it.copy(isAnnotationMode = false, annotationText = null, annotationImageUri = null) }
                        }
                    } else {
                         _cards.value = _cards.value.map { it.copy(isAnnotationMode = false, annotationText = null, annotationImageUri = null) }
                    }
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Fallback to clear annotations on error
                    _cards.value = _cards.value.map { it.copy(isAnnotationMode = false, annotationText = null, annotationImageUri = null) }
                } finally {
                    _isProcessing.value = false
                }
            }
        }
    }
}
