package lol.mony.harmony.data.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Web NotificationSettings stores UTC time-of-day and displays today's local clock time. */
object AccountQuietHours {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private val wire = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun local(storedUtc: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        LocalTime.parse(storedUtc).atDate(today).atZone(ZoneOffset.UTC).withZoneSameInstant(zone).format(clock)

    fun utc(local: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String =
        LocalTime.parse(local).atDate(today).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(wire)
}
