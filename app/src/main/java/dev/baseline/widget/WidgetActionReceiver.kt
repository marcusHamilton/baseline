package dev.baseline.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.baseline.garmin.KeyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action != ACTION_WIDGET_SYNC &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                handleIntent(context, intent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "widget receiver: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending?.finish()
            }
        }
    }

    internal suspend fun handleIntent(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContainer = WidgetUpdater.resolveContainer(context) ?: return
        when (action) {
            ACTION_WIDGET_SYNC -> {
                val status = appContainer.apiKeyVault.status.first()
                if (status !is KeyStatus.Missing) {
                    // The worker flips the pill to Syncing once it runs; forcing it here would stick while offline.
                    appContainer.scheduler.syncNow()
                    appContainer.widgetUpdater.updateAll()
                } else {
                    appContainer.widgetUpdater.updateAll()
                }
            }

            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                appContainer.dateTicker.refresh()
                appContainer.widgetUpdater.updateAll()
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_SYNC: String = "dev.baseline.action.WIDGET_SYNC"
        private const val TAG = "WidgetActionReceiver"

        fun syncPendingIntent(context: Context): PendingIntent {
            val intent =
                Intent(context, WidgetActionReceiver::class.java).apply {
                    action = ACTION_WIDGET_SYNC
                }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
