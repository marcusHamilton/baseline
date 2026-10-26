package dev.baseline

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import dev.baseline.sync.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BaselineApp :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration = Configuration.Builder().build()

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.createChannel(this)
        // Midnight and clock/timezone changes while the process is alive; MainActivity.onResume covers the rest.
        val dateChanged =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = container.dateTicker.refresh()
            }
        ContextCompat.registerReceiver(
            this,
            dateChanged,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val log = CoroutineExceptionHandler { _, t -> Log.w(TAG, "background scope: ${t.javaClass.simpleName}: ${t.message}") }
        // Re-applies when the key is saved (onboarding) or removed, and when the interval setting changes.
        CoroutineScope(SupervisorJob() + Dispatchers.Default + log).launch {
            container.scheduleInterval.collect {
                try {
                    container.scheduler.apply(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // one bad apply must not end the collector
                    Log.w(TAG, "schedule: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "BaselineApp"
    }
}
