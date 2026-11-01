package dev.baseline.ai

/** Which engines the assistant may use (spec §3.1). */
enum class AiMode { OFF, ON_DEVICE, CLOUD, HYBRID }

/** One row of the mode picker: greyed out with [reason] when [enabled] is false. */
data class ModeOption(val mode: AiMode, val enabled: Boolean, val reason: String?)

/** Defaults and transitions for [AiMode] (spec §3.1). `null` means undecided. */
object AiModes {
    private const val NO_NANO = "Not supported on this phone"
    private const val NO_KEY = "Add an API key"

    /** First launch, once Nano's status is known. */
    fun initial(nanoSupported: Boolean): AiMode = if (nanoSupported) AiMode.ON_DEVICE else AiMode.OFF

    fun afterKeySaved(current: AiMode?, nanoSupported: Boolean): AiMode = when (current) {
        AiMode.CLOUD, AiMode.HYBRID -> current
        AiMode.OFF, AiMode.ON_DEVICE, null -> if (nanoSupported) AiMode.HYBRID else AiMode.CLOUD
    }

    fun afterKeyRemoved(current: AiMode?, nanoSupported: Boolean): AiMode = when (current) {
        AiMode.ON_DEVICE, AiMode.OFF -> current
        AiMode.CLOUD, AiMode.HYBRID, null -> initial(nanoSupported)
    }

    fun afterNanoUnavailable(current: AiMode): AiMode = when (current) {
        AiMode.ON_DEVICE -> AiMode.OFF
        AiMode.HYBRID -> AiMode.CLOUD
        AiMode.OFF, AiMode.CLOUD -> current
    }

    /** OFF, ON_DEVICE, CLOUD, HYBRID in that order; HYBRID lists every missing requirement. */
    fun options(nanoSupported: Boolean, keySet: Boolean): List<ModeOption> {
        val hybridReason = listOfNotNull(NO_NANO.takeUnless { nanoSupported }, NO_KEY.takeUnless { keySet })
        return listOf(
            ModeOption(AiMode.OFF, true, null),
            ModeOption(AiMode.ON_DEVICE, nanoSupported, NO_NANO.takeUnless { nanoSupported }),
            ModeOption(AiMode.CLOUD, keySet, NO_KEY.takeUnless { keySet }),
            ModeOption(AiMode.HYBRID, hybridReason.isEmpty(), hybridReason.takeIf { it.isNotEmpty() }?.joinToString(" · ")),
        )
    }
}
