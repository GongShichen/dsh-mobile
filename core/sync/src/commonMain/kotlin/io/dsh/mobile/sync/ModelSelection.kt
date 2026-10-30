package io.dsh.mobile.sync

import io.dsh.mobile.model.MobileState
import kotlinx.serialization.json.*

/** Mirrors Web's modelSelection.next -> catalog.default precedence; never guesses supported efforts. */
data class ModelChoice(val provider: String, val model: String, val effort: String? = null)
data class ReasoningChoice(val id: String?, val name: String, val description: String? = null)

fun currentModelChoice(state: MobileState): ModelChoice? {
    val selected = state.projections?.obj("modelSelection")?.obj("next") ?: state.defaultModel ?: return null
    val provider = selected.string("provider") ?: return null
    val model = selected.string("model") ?: return null
    val entry = state.models.firstOrNull { it.string("provider") == provider && it.string("model") == model }
    return ModelChoice(provider, model, selected.string("reasoningEffort") ?: entry?.obj("reasoning")?.string("defaultEffort"))
}

fun modelReasoningChoices(model: JsonObject): List<ReasoningChoice> {
    val reasoning = model.obj("reasoning") ?: return emptyList()
    val choices = reasoning.array("efforts").mapNotNull { raw ->
        val entry = raw as? JsonObject ?: return@mapNotNull null
        val id = entry.string("id") ?: return@mapNotNull null
        ReasoningChoice(id, entry.string("name") ?: id, entry.string("description"))
    }
    return if (reasoning.string("defaultEffort") == null) listOf(ReasoningChoice(null, "模型默认")) + choices else choices
}

fun modelSelectionParameters(sessionId: String, choice: ModelChoice): JsonObject = buildJsonObject {
    put("sessionId", sessionId); put("provider", choice.provider); put("model", choice.model)
    choice.effort?.let { put("reasoningEffort", it) }
}
