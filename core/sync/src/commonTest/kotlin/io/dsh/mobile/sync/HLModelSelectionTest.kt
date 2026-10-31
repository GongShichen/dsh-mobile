package io.dsh.mobile.sync

import io.dsh.mobile.model.HLState
import kotlinx.serialization.json.*
import kotlin.test.*

class HLModelSelectionTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private val model = obj("""{"provider":"p","model":"m","reasoning":{"defaultEffort":"high","efforts":[{"id":"off","name":"Off"},{"id":"high","name":"High"},{"id":"max","name":"Max"}]}}""")
    @Test fun desktopProjectionOverridesCatalogDefaultAndUpdatesOnRemoteChanges() {
        val state = HLState(models = listOf(model), defaultModel = obj("""{"provider":"p","model":"m"}"""))
        assertEquals(HLModelChoice("p", "m", "high"), currentModelChoice(state))
        val desktop = state.copy(projections = obj("""{"modelSelection":{"next":{"provider":"p","model":"m","reasoningEffort":"max"}}}"""))
        assertEquals("max", currentModelChoice(desktop)?.effort)
        assertEquals("off", currentModelChoice(desktop.copy(projections = obj("""{"modelSelection":{"next":{"provider":"p","model":"m","reasoningEffort":"off"}}}""")))?.effort)
    }
    @Test fun onlyHostAdvertisedEffortsAreOffered() {
        assertEquals(listOf("off", "high", "max"), modelReasoningChoices(model).map { it.id })
        assertTrue(modelReasoningChoices(obj("""{"model":"plain"}""")).isEmpty())
        assertEquals(listOf(null, "custom"), modelReasoningChoices(obj("""{"reasoning":{"efforts":[{"id":"custom","name":"Custom"}]}}""")).map { it.id })
    }
    @Test fun providerDefaultOmitsEffortInsteadOfSendingNullOrInventingHigh() {
        assertEquals(obj("""{"sessionId":"s","provider":"p","model":"m"}"""), modelSelectionParameters("s", HLModelChoice("p", "m")))
        assertEquals("max", modelSelectionParameters("s", HLModelChoice("p", "m", "max"))["reasoningEffort"]?.jsonPrimitive?.content)
    }
}
