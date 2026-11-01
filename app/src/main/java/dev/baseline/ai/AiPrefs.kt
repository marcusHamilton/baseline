package dev.baseline.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The user's [AiMode]; null until it has been decided (spec §3.1, §4.4). */
class AiPrefs(private val store: DataStore<Preferences>) {
    val mode: Flow<AiMode?> =
        store.data
            .map { p -> p[MODE]?.let { name -> AiMode.entries.firstOrNull { it.name == name } } }
            .distinctUntilChanged()

    suspend fun setMode(mode: AiMode) {
        store.edit { it[MODE] = mode.name }
    }

    /** True once the first-run Nano prompt has been answered or found unnecessary (spec: AI assistant §3.1). */
    val promptDone: Flow<Boolean> = store.data.map { it[PROMPT_DONE] == true }.distinctUntilChanged()

    suspend fun setPromptDone() {
        store.edit { it[PROMPT_DONE] = true }
    }

    private companion object {
        val MODE = stringPreferencesKey("ai_mode")
        val PROMPT_DONE = booleanPreferencesKey("ai_prompt_done")
    }
}
