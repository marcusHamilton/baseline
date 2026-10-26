package dev.baseline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Wall clock that reads the device zone at every call, so a timezone change is picked up without a restart. */
object SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}

/**
 * The current local date as a flow, so a process that outlives midnight does not keep showing yesterday as "today".
 * Call [refresh] when the app returns to the foreground and on date/time/timezone broadcasts.
 */
class DateTicker(private val clock: Clock) {
    private val _today = MutableStateFlow(LocalDate.now(clock))
    val today: StateFlow<LocalDate> = _today

    private val _zone = MutableStateFlow(clock.zone)
    val zone: StateFlow<ZoneId> = _zone

    fun refresh() {
        _today.value = LocalDate.now(clock)
        _zone.value = clock.zone
    }
}
