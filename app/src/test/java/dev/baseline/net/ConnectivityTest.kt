package dev.baseline.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class ConnectivityTest {
    @Test
    fun emitsCurrentStateThenFollowsTheDefaultNetworkCallback() = runBlocking {
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(cm)
        val network = cm.activeNetwork!!

        fun caps(validated: Boolean) = ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (validated) shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadow.setNetworkCapabilities(network, caps(validated = true))
        val flow = cm.onlineFlow()

        val collected = async(kotlinx.coroutines.Dispatchers.Default) { flow.take(3).toList() }
        withTimeout(10_000) { while (shadow.networkCallbacks.isEmpty()) kotlinx.coroutines.yield() }
        val callback = shadow.networkCallbacks.single()
        callback.onCapabilitiesChanged(network, caps(validated = false)) // e.g. a captive portal
        callback.onCapabilitiesChanged(network, caps(validated = true))

        val states = withTimeout(10_000) { collected.await() }
        assertEquals(listOf(true, false, true), states)
    }
}
