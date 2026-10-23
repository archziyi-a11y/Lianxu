package com.example.lianxu.data.model

import com.squareup.moshi.JsonClass
import java.util.UUID

enum class CardSpan(val span: Int) { SMALL(2), MEDIUM(3), LARGE(6) }

// Transitional render cache. Stage two replaces value strings with component/entity references.
@JsonClass(generateAdapter = true)
data class DashboardCard(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val value: String,
    val span: CardSpan,
    val revision: Int = 1,
    val isAnnotationMode: Boolean = false,
    val annotationText: String? = null,
    val annotationImageUri: String? = null,
    val attachmentId: String? = null
)
