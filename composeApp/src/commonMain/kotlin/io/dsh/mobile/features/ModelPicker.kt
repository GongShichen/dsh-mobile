package io.dsh.mobile.features

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dsh.mobile.model.MobileState
import io.dsh.mobile.sync.*
import kotlinx.serialization.json.*

/** Separate horizontal controls, both backed by the desktop's current model-selection projection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPicker(state: MobileState, repository: MobileRepository, busy: Boolean, run: RunAction, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissInput() { focus.clearFocus(); keyboard?.hide() }
    var modelsOpen by remember { mutableStateOf(false) }
    var effortsOpen by remember { mutableStateOf(false) }
    val current = currentModelChoice(state)
    val currentEntry = state.models.firstOrNull { it.string("provider") == current?.provider && it.string("model") == current?.model }
    val currentName = currentEntry?.string("name") ?: current?.model ?: "模型"
    val efforts = currentEntry?.let(::modelReasoningChoices).orEmpty()
    val effortName = efforts.firstOrNull { it.id == current?.effort }?.name ?: current?.effort ?: "默认"
    val enabled = state.connected && !busy
    val select: (ModelChoice) -> Unit = { choice -> run {
        val id = state.selectedSessionId ?: repository.createSession()
        repository.call("models.select", modelSelectionParameters(id, choice))
        modelsOpen = false; effortsOpen = false
    } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = enabled && state.models.isNotEmpty(), onClick = { dismissInput(); modelsOpen = true },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "选择模型" },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(currentName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp)); NavigationIcon("DOWN", 12.dp)
        }
        if (efforts.isNotEmpty()) {
            VerticalDivider(Modifier.height(14.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Box {
                TextButton(enabled = enabled, onClick = { dismissInput(); effortsOpen = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "思考强度" },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    NavigationIcon("THINK", 14.dp); Spacer(Modifier.width(4.dp))
                    Text(effortName, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp)); NavigationIcon("DOWN", 12.dp)
                }
                DropdownMenu(expanded = effortsOpen, onDismissRequest = { effortsOpen = false }) {
                    Text("思考强度", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    efforts.forEach { effort ->
                        DropdownMenuItem(text = { Column {
                            Text(effort.name)
                            effort.description?.let { Text(it, Modifier.widthIn(max = 240.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } }, trailingIcon = { if (effort.id == current?.effort) NavigationIcon("CHECK", 16.dp) }, enabled = enabled,
                            onClick = { current?.let { select(it.copy(effort = effort.id)) } })
                    }
                }
            }
        }
    }
    if (!modelsOpen) return
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { if (!busy) modelsOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("选择模型", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("与电脑上的当前会话同步", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(top = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                state.models.groupBy { it.string("providerName") ?: it.string("provider").orEmpty() }.forEach { (providerName, models) ->
                    item { Text(providerName, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(models, key = { "${it.string("provider")}:${it.string("model")}" }) { model ->
                        val provider = model.string("provider") ?: return@items
                        val id = model.string("model") ?: return@items
                        val selected = current?.provider == provider && current.model == id
                        val choice = if (selected) current!! else ModelChoice(provider, id, model.obj("reasoning")?.string("defaultEffort"))
                        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { select(choice) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(model.string("name") ?: id, style = MaterialTheme.typography.bodyLarge)
                                model.string("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            }
                            RadioButton(selected = selected, onClick = { select(choice) }, enabled = enabled)
                        }
                    }
                }
            }
        }
    }
}
