package dev.baseline.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import dev.baseline.ai.context.AskScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which Ask sheet is open, held in the Activity's ViewModel store so it survives configuration changes (rotation, dark mode, font
 * size): the sheet reopens and its chat is still there (spec §3.6). [store] holds the open sheet's own ViewModels. [open] gives every
 * sheet a fresh one, and [close] or the Activity going away clears it, which cancels any request in flight and discards the chat.
 */
class AskHostViewModel : ViewModel() {
    private val _askFor = MutableStateFlow<AskScope?>(null)
    val askFor: StateFlow<AskScope?> = _askFor

    /** The open sheet's ViewModel store; null while no sheet is open. */
    var store: ViewModelStore? = null
        private set

    fun open(scope: AskScope) {
        store?.clear()
        store = ViewModelStore()
        _askFor.value = scope
    }

    fun close() {
        _askFor.value = null
        store?.clear()
        store = null
    }

    override fun onCleared() {
        store?.clear()
        store = null
    }
}
