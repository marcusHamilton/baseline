package dev.baseline.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    private companion object {
        val MODE = stringPreferencesKey("ai_mode")
    }
}
