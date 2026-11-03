package com.myen88.wearkey.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AutoKeyEvent(
    val atMillis: Long,
    val rssi: Int,
    val action: String,
    val result: String,
) {
    fun line(): String {
        val clock = Instant.ofEpochMilli(atMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
            .format(CLOCK)
        val signal = if (rssi in -120..-1) rssi.toString() else "none"
        return "$clock  $signal  $action  $result"
    }

    companion object {
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

/** The Auto key service publishes its controller so a button can move the latches. */
object AutoKeyPresence {
    @Volatile
    var controller: com.myen88.wearkey.protocol.presence.PresenceController? = null
}

/**
 * Last 20 Auto key decisions. Identical lines inside four seconds collapse
 * so a 1 Hz challenge deny does not push a real unlock off the list.
 */
object AutoKeyLog {
    private val events = ArrayDeque<AutoKeyEvent>()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(rssi: Int, action: String, result: String) {
        val now = System.currentTimeMillis()
        synchronized(events) {
            val last = events.lastOrNull()
            if (last != null && last.action == action && last.result == result && now - last.atMillis < 4_000) {
                return
            }
            events.addLast(AutoKeyEvent(now, rssi, action, result))
            while (events.size > 20) events.removeFirst()
            _lines.value = events.map { it.line() }.asReversed()
        }
    }
}
