package dev.baseline.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.ai.AiKeyEntry
import dev.baseline.ai.AiKeyVault
import dev.baseline.ai.ModelsResult
import dev.baseline.ai.TestOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

/** The steps of adding a key (spec: AI assistant §3.3). */
sealed interface AiKeyStep {
    data object Connection : AiKeyStep

    /** Fetching `/models`. */
    data object Loading : AiKeyStep

    data class Model(val result: ModelsResult) : AiKeyStep

    data object Testing : AiKeyStep

    data class Done(val tools: Boolean) : AiKeyStep
}

/**
 * The add-key / choose-model flow. The key being tested lives only in [key], a private field of this ViewModel: it isn't in a SavedStateHandle,
 * a saveable or any state the UI can read, and it's dropped once it has been saved. With [startAtModel] ("Change model") the stored URL and key
 * are loaded from the vault instead, and only the model is re-tested. A key under test never touches the vault: [AiKeyEntry] saves it only
 * after it passes.
 */
class AiKeyViewModel(private val entry: AiKeyEntry, private val vault: AiKeyVault, private val startAtModel: Boolean) : ViewModel() {
    private val _step = MutableStateFlow<AiKeyStep>(if (startAtModel) AiKeyStep.Loading else AiKeyStep.Connection)
    val step: StateFlow<AiKeyStep> = _step

    /** Our own wording for what just failed, or null. Never provider text, never the key. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var baseUrl: HttpUrl? = null
    private var key: String? = null
    private var models: ModelsResult? = null

    // True while the stored key is the one in use; typing a new connection replaces it.
    private var useStored = startAtModel

    init {
        if (startAtModel) {
            viewModelScope.launch {
                val config = vault.config()
                if (config == null) {
                    _step.value = AiKeyStep.Connection
                } else {
                    baseUrl = config.baseUrl
                    key = config.key
                    loadModels(config.baseUrl, config.key)
                }
            }
        }
    }

    fun submitConnection(baseUrl: String, key: String) {
        _error.value = null
        val url = entry.parseUrl(baseUrl)
        if (url == null) {
            _error.value = BAD_URL
            return
        }
        val clean = entry.cleanKey(key)
        if (clean == null) {
            _error.value = BAD_KEY
            return
        }
        useStored = false
        this.baseUrl = url
        this.key = clean
        _step.value = AiKeyStep.Loading
        viewModelScope.launch { loadModels(url, clean) }
    }

    fun pickModel(id: String) {
        val url = baseUrl
        val key = key
        val model = id.trim()
        if (url == null || key == null || model.isEmpty()) return
        _error.value = null
        _step.value = AiKeyStep.Testing
        viewModelScope.launch {
            when (val outcome = if (useStored) entry.changeModel(model) else entry.saveAndTest(url, key, model)) {
                is TestOutcome.Saved -> {
                    this@AiKeyViewModel.key = null
                    _step.value = AiKeyStep.Done(outcome.toolsSupported)
                }

                // Back on the model list with the pick intact, so a different model can be tried.
                is TestOutcome.Failed -> {
                    _error.value = outcome.message
                    _step.value = AiKeyStep.Model(models ?: ModelsResult.FreeText)
                }
            }
        }
    }

    /** From the model list back to the connection form. False (and nothing changes) for "Change model", which has no form to go back to. */
    fun backToConnection(): Boolean {
        if (useStored) return false
        _error.value = null
        key = null
        _step.value = AiKeyStep.Connection
        return true
    }

    private suspend fun loadModels(url: HttpUrl, key: String) {
        when (val result = entry.listModels(url, key)) {
            is ModelsResult.Listed, ModelsResult.FreeText -> {
                models = result
                _step.value = AiKeyStep.Model(result)
            }

            ModelsResult.KeyRejected -> backWith(KEY_REJECTED)

            is ModelsResult.Unreachable -> backWith("Couldn't reach ${result.host}.")
        }
    }

    private fun backWith(message: String) {
        key = null
        _error.value = message
        _step.value = AiKeyStep.Connection
    }

    private companion object {
        const val BAD_URL = "Use an https:// address."
        const val BAD_KEY = "That doesn't look like a complete key."
        const val KEY_REJECTED = "Key rejected."
    }
}
