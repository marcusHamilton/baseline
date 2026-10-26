package dev.baseline.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.baseline.data.Repository
import dev.baseline.data.db.ActivityEntity
import dev.baseline.data.db.ActivityScoreEntity
import dev.baseline.data.db.ActivityStreamEntity
import dev.baseline.data.db.StrengthSetEntity
import dev.baseline.data.db.SwimLengthEntity
import dev.baseline.data.db.TaskStatus
import dev.baseline.garmin.GarminEndpoint
import dev.baseline.sync.SyncScheduler
import dev.baseline.ui.format.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One activity's detail. A missing FIT file is queued once (priority 0) and the sections arrive through the stream Flow. */
class ActivityViewModel(private val repo: Repository, private val scheduler: SyncScheduler, val id: Long) : ViewModel() {
    private class Core(val activity: ActivityEntity?, val score: ActivityScoreEntity?, val stream: ActivityStreamEntity?)

    private class Rest(val lengths: List<SwimLengthEntity>, val sets: List<StrengthSetEntity>, val task: TaskStatus?, val imperial: Boolean)

    val state: StateFlow<ActivityUiState> =
        combine(
            combine(repo.activity(id), repo.activityScore(id), repo.activityStream(id), ::Core),
            combine(repo.swimLengths(id), repo.strengthSets(id), repo.taskStatus(GarminEndpoint.FIT, id.toString()), repo.imperial, ::Rest),
        ) { c, r -> buildActivityState(c.activity, c.score, c.stream, r.lengths, r.sets, r.task, Formatters(r.imperial)) }
            .flowOn(Dispatchers.Default) // unpacking and splitting a long stream is real work
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(null, emptyList(), emptyList(), loading = true))

    init {
        // Once per screen instance. An EMPTY task means Garmin has no file for this activity; asking again would not change that.
        viewModelScope.launch {
            val key = id.toString()
            if (repo.activity(id).first() == null || repo.activityStream(id).first() != null) return@launch
            if (repo.taskStatus(GarminEndpoint.FIT, key).first() == TaskStatus.EMPTY) return@launch
            repo.requestFit(id)
            scheduler.syncNow()
        }
    }
}
