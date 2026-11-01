package dev.baseline.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.baseline.R
import dev.baseline.ai.DEFAULT_AI_BASE_URL
import dev.baseline.ai.ModelInfo
import dev.baseline.ai.ModelsResult
import dev.baseline.ai.SUGGESTED_MODEL
import dev.baseline.ai.formatPricePerQuestion
import dev.baseline.ui.components.CircleButton
import dev.baseline.ui.theme.BarlowCondensed
import dev.baseline.ui.theme.ChipActive
import dev.baseline.ui.theme.RecoveryAmber
import dev.baseline.ui.theme.RecoveryGreen
import dev.baseline.ui.theme.RecoveryRed
import dev.baseline.ui.theme.SleepPurple
import dev.baseline.ui.theme.TextBody
import dev.baseline.ui.theme.TextMuted
import dev.baseline.ui.theme.TextPrimary

/**
 * Adding a cloud key and choosing its model (spec: AI assistant §3.3): connection, model list, test, outcome. [onBack] leaves for Settings.
 *
 * The URL is saveable. The key is plain `remember` state, never saved with the instance state; once submitted it lives only in [vm].
 */
@Composable
fun AiKeyScreen(vm: AiKeyViewModel, onBack: () -> Unit) {
    val step by vm.step.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(DEFAULT_AI_BASE_URL) }
    var key by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var modelId by rememberSaveable { mutableStateOf("") }
    // The saved key is no longer needed on screen once it works.
    LaunchedEffect(step) { if (step is AiKeyStep.Done) key = "" }

    val back = {
        when (step) {
            is AiKeyStep.Model -> if (!vm.backToConnection()) onBack()
            AiKeyStep.Testing -> Unit
            else -> onBack()
        }
    }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 16.dp), Arrangement.spacedBy(16.dp)) {
        Header(step, back)
        when (val s = step) {
            AiKeyStep.Connection -> ConnectionStep(url, { url = it }, key, { key = it }, error) { vm.submitConnection(url, key) }

            AiKeyStep.Loading -> Busy("Loading models…")

            is AiKeyStep.Model -> when (val result = s.result) {
                is ModelsResult.Listed -> ModelList(result.models, query, { query = it }, picked, { picked = it }, error) { vm.pickModel(it) }
                else -> ModelIdField(modelId, { modelId = it }, error) { vm.pickModel(modelId) }
            }

            AiKeyStep.Testing -> Busy("Testing…")

            is AiKeyStep.Done -> Outcome(s.tools, onBack)
        }
    }
}

@Composable
private fun Header(step: AiKeyStep, onBack: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircleButton(R.drawable.ic_chevron_left, "Back", true, onBack)
        Column {
            Text(
                "CLOUD AI",
                Modifier.semantics { heading() },
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                letterSpacing = 0.88.sp,
            )
            Text(
                when (step) {
                    AiKeyStep.Connection -> "Provider and key"
                    is AiKeyStep.Model -> "Choose a model"
                    else -> ""
                },
                fontSize = 13.sp,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun ConnectionStep(url: String, onUrl: (String) -> Unit, key: String, onKey: (String) -> Unit, error: String?, onContinue: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(16.dp)) {
        Text(
            "Baseline sends your questions to this address with your API key. The key stays on this phone, encrypted.",
            fontSize = 14.sp,
            color = TextBody,
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            label = { Text("Base URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("ai-base-url"),
        )
        OutlinedTextField(
            value = key,
            onValueChange = onKey,
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("ai-api-key"),
        )
        ErrorText(error)
        Button(
            onClick = onContinue,
            enabled = key.isNotBlank() && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = primaryButtonColors(),
        ) { Text("Continue", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ColumnScope.ModelList(
    models: List<ModelInfo>,
    query: String,
    onQuery: (String) -> Unit,
    picked: String?,
    onPick: (String) -> Unit,
    error: String?,
    onTest: (String) -> Unit,
) {
    // Preselect the suggested model, but only until the user picks something.
    val selected = picked ?: SUGGESTED_MODEL.takeIf { id -> models.any { it.id == id } }
    val shown = remember(models, query) { models.filter { it.id.contains(query.trim(), ignoreCase = true) } }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        label = { Text("Search models") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("ai-model-search"),
    )
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(shown, key = { it.id }) { model -> ModelRow(model, model.id == selected) { onPick(model.id) } }
    }
    ErrorText(error)
    TestButton(selected != null) { selected?.let(onTest) }
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ai-model-${model.id}").selectable(selected, role = Role.RadioButton, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = SleepPurple, unselectedColor = TextMuted))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(model.id, fontSize = 14.sp, color = TextPrimary)
            model.pricePerQuestion?.let { Text(formatPricePerQuestion(it), fontSize = 12.sp, color = TextMuted) }
        }
        if (model.tools == true) {
            Text(
                "Tools",
                Modifier.clip(RoundedCornerShape(6.dp)).background(ChipActive).padding(horizontal = 8.dp, vertical = 2.dp),
                fontSize = 11.sp,
                color = SleepPurple,
            )
        }
    }
}

@Composable
private fun ColumnScope.ModelIdField(modelId: String, onModelId: (String) -> Unit, error: String?, onTest: () -> Unit) {
    Text("This provider didn't list its models. Type the ID of the model to use.", fontSize = 14.sp, color = TextBody)
    OutlinedTextField(
        value = modelId,
        onValueChange = onModelId,
        label = { Text("Model ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("ai-model-id"),
    )
    ErrorText(error)
    Spacer(Modifier.weight(1f))
    TestButton(modelId.isNotBlank(), onTest)
}

@Composable
private fun TestButton(enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = primaryButtonColors()) {
        Text("Test and save", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Busy(label: String) {
    Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SleepPurple)
        Text(label, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 14.sp, color = TextBody)
    }
}

@Composable
private fun ColumnScope.Outcome(tools: Boolean, onDone: () -> Unit) {
    Text(
        if (tools) "✓ Key works · tools supported" else "⚠ This model can't look up your history; answers will use the page's data only.",
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = if (tools) RecoveryGreen else RecoveryAmber,
    )
    Spacer(Modifier.weight(1f))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = primaryButtonColors()) {
        Text("Done", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = RecoveryRed, fontSize = 14.sp)
}
