package dev.baseline.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModeTest {
    @Test
    fun initialDependsOnNano() {
        assertEquals(AiMode.ON_DEVICE, AiModes.initial(true))
        assertEquals(AiMode.OFF, AiModes.initial(false))
    }

    @Test
    fun afterKeySavedMovesOffOnDeviceAndUndecided() {
        for (current in listOf(AiMode.OFF, AiMode.ON_DEVICE, null)) {
            assertEquals(AiMode.HYBRID, AiModes.afterKeySaved(current, nanoSupported = true))
            assertEquals(AiMode.CLOUD, AiModes.afterKeySaved(current, nanoSupported = false))
        }
    }

    @Test
    fun afterKeySavedLeavesCloudAndHybridAlone() {
        for (nano in listOf(true, false)) {
            assertEquals(AiMode.CLOUD, AiModes.afterKeySaved(AiMode.CLOUD, nano))
            assertEquals(AiMode.HYBRID, AiModes.afterKeySaved(AiMode.HYBRID, nano))
        }
    }

    @Test
    fun afterKeyRemovedFallsBackToOnDeviceOrOff() {
        for (current in listOf(AiMode.CLOUD, AiMode.HYBRID)) {
            assertEquals(AiMode.ON_DEVICE, AiModes.afterKeyRemoved(current, nanoSupported = true))
            assertEquals(AiMode.OFF, AiModes.afterKeyRemoved(current, nanoSupported = false))
        }
    }

    @Test
    fun afterKeyRemovedLeavesOnDeviceAndOffAlone() {
        for (nano in listOf(true, false)) {
            assertEquals(AiMode.ON_DEVICE, AiModes.afterKeyRemoved(AiMode.ON_DEVICE, nano))
            assertEquals(AiMode.OFF, AiModes.afterKeyRemoved(AiMode.OFF, nano))
        }
    }

    @Test
    fun afterKeyRemovedTreatsUndecidedLikeFirstLaunch() {
        assertEquals(AiMode.ON_DEVICE, AiModes.afterKeyRemoved(null, nanoSupported = true))
        assertEquals(AiMode.OFF, AiModes.afterKeyRemoved(null, nanoSupported = false))
    }

    @Test
    fun afterNanoUnavailable() {
        assertEquals(AiMode.OFF, AiModes.afterNanoUnavailable(AiMode.ON_DEVICE))
        assertEquals(AiMode.CLOUD, AiModes.afterNanoUnavailable(AiMode.HYBRID))
        assertEquals(AiMode.OFF, AiModes.afterNanoUnavailable(AiMode.OFF))
        assertEquals(AiMode.CLOUD, AiModes.afterNanoUnavailable(AiMode.CLOUD))
    }

    @Test
    fun optionsWithNothingAvailable() {
        val options = AiModes.options(nanoSupported = false, keySet = false)
        assertEquals(listOf(AiMode.OFF, AiMode.ON_DEVICE, AiMode.CLOUD, AiMode.HYBRID), options.map { it.mode })
        assertEquals(ModeOption(AiMode.OFF, true, null), options[0])
        assertEquals(ModeOption(AiMode.ON_DEVICE, false, "Not supported on this phone"), options[1])
        assertEquals(ModeOption(AiMode.CLOUD, false, "Add an API key"), options[2])
        assertEquals(ModeOption(AiMode.HYBRID, false, "Not supported on this phone · Add an API key"), options[3])
    }

    @Test
    fun optionsWithOnlyNano() {
        val options = AiModes.options(nanoSupported = true, keySet = false)
        assertEquals(ModeOption(AiMode.ON_DEVICE, true, null), options[1])
        assertEquals(ModeOption(AiMode.CLOUD, false, "Add an API key"), options[2])
        assertEquals(ModeOption(AiMode.HYBRID, false, "Add an API key"), options[3])
    }

    @Test
    fun optionsWithOnlyKey() {
        val options = AiModes.options(nanoSupported = false, keySet = true)
        assertEquals(ModeOption(AiMode.ON_DEVICE, false, "Not supported on this phone"), options[1])
        assertEquals(ModeOption(AiMode.CLOUD, true, null), options[2])
        assertEquals(ModeOption(AiMode.HYBRID, false, "Not supported on this phone"), options[3])
    }

    @Test
    fun optionsWithBoth() {
        val options = AiModes.options(nanoSupported = true, keySet = true)
        assertTrue(options.all { it.enabled && it.reason == null })
    }
}
