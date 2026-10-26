package dev.baseline.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.garmin.ApiKeyVault
import dev.baseline.garmin.GarminResult
import dev.baseline.garmin.KeyTest
import dev.baseline.garmin.saveAndTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface OnboardingState {
    data object Idle : OnboardingState

    data object Testing : OnboardingState

    data class Error(val message: String) : OnboardingState

    data object Done : OnboardingState
}

/** First-run key entry: see [saveAndTest]; the key is only stored once the test passes. */
class OnboardingViewModel(private val vault: ApiKeyVault, private val clearToken: suspend () -> Unit = {}, private val testKey: KeyTest) : ViewModel() {
    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Idle)
    val state: StateFlow<OnboardingState> = _state

    fun submit(key: String, lifetimeDays: Int) {
        val trimmed = key.trim()
        if (trimmed.isEmpty() || _state.value == OnboardingState.Testing) return
        _state.value = OnboardingState.Testing
        viewModelScope.launch {
            val failure = vault.saveAndTest(trimmed, lifetimeDays, testKey, clearToken)
            _state.value = if (failure == null) OnboardingState.Done else OnboardingState.Error(failure)
        }
    }
}
