package com.example.lianxu.data.model

import com.squareup.moshi.JsonClass
import java.util.UUID

enum class CardSpan(val span: Int) { SMALL(2), MEDIUM(3), LARGE(6) }

// Cards own composition and references; business values are resolved from persisted sources.
@JsonClass(generateAdapter = true)
data class DashboardCard(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val value: String = "",
    val span: CardSpan,
    val revision: Int = 1,
    val isAnnotationMode: Boolean = false,
    val annotationText: String? = null,
    val annotationImageUri: String? = null,
    val attachmentId: String? = null,
    val blocks: List<BoardComponent> = emptyList(),
    val entityRefs: List<EntityRef> = emptyList(),
    val annotationAnchor: AnnotationAnchor? = null,
    val managedBy: String = "local",
    val inputValues: Map<String, String> = emptyMap(),
    val followUpId: String? = null,
    val invitationId: String? = null
)

@JsonClass(generateAdapter = true)
data class EntityRef(val type: String, val id: String, val revision: Int = 1)
@JsonClass(generateAdapter = true)
data class ContentRef(val entityRef: EntityRef, val fieldPath: String)
@JsonClass(generateAdapter = true)
data class ChoiceOption(val key: String, val label: String)
@JsonClass(generateAdapter = true)
data class ComponentContent(val label: String? = null, val text: String? = null,
    val contentRef: ContentRef? = null, val calculationId: String? = null, val resultPath: String? = null,
    val unit: String? = null, val entityRefs: List<EntityRef> = emptyList(), val mealId: String? = null,
    val options: List<ChoiceOption> = emptyList(), val selectedKey: String? = null, val attachmentId: String? = null,
    val inputKind: String? = null, val inputFields: List<String> = emptyList(), val suggestedName: String? = null)
@JsonClass(generateAdapter = true)
data class CardAction(val actionId: String, val label: String, val kind: String, val targetRef: EntityRef,
    val mealId: String? = null, val value: String? = null)
@JsonClass(generateAdapter = true)
data class BoardComponent(val componentId: String, val type: String, val content: ComponentContent,
    val entityRef: EntityRef? = null, val actions: List<CardAction> = emptyList())
@JsonClass(generateAdapter = true)
data class AnnotationAnchor(val boardRevision: Int, val cardRevision: Int, val readableContent: String,
    val componentsJson: String, val title: String = "")
