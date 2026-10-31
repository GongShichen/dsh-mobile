package io.dsh.mobile.sync

import io.dsh.mobile.model.HLState
import kotlinx.serialization.json.*

/** Mirrors Web's modelSelection.next -> catalog.default precedence; never guesses supported efforts. */
data class HLModelChoice(val provider: String, val model: String, val effort: String? = null)
data class HLReasoningChoice(val id: String?, val name: String, val description: String? = null)

fun currentModelChoice(state: HLState): HLModelChoice? {
    val selected = state.projections?.obj("modelSelection")?.obj("next") ?: state.defaultModel ?: return null
    val provider = selected.string("provider") ?: return null
    val model = selected.string("model") ?: return null
    val entry = state.models.firstOrNull { it.string("provider") == provider && it.string("model") == model }
    return HLModelChoice(provider, model, selected.string("reasoningEffort") ?: entry?.obj("reasoning")?.string("defaultEffort"))
}

fun modelReasoningChoices(model: JsonObject): List<HLReasoningChoice> {
    val reasoning = model.obj("reasoning") ?: return emptyList()
    val choices = reasoning.array("efforts").mapNotNull { raw ->
        val entry = raw as? JsonObject ?: return@mapNotNull null
        val id = entry.string("id") ?: return@mapNotNull null
        HLReasoningChoice(id, entry.string("name") ?: id, entry.string("description"))
    }
    return if (reasoning.string("defaultEffort") == null) listOf(HLReasoningChoice(null, "模型默认")) + choices else choices
}

fun modelSelectionParameters(sessionId: String, choice: HLModelChoice): JsonObject = buildJsonObject {
    put("sessionId", sessionId); put("provider", choice.provider); put("model", choice.model)
    choice.effort?.let { put("reasoningEffort", it) }
}
