package ma.goldmaroc18k.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Calendrier d'actualisation : 07:00, 10:00, 13:00, 18:00, du lundi au samedi,
 * fuseau Africa/Casablanca (indépendant du fuseau du téléphone).
 * Les changements d'heure (Maroc : retour à UTC+0 pendant le Ramadan) sont gérés par java.time / tzdata.
 */
object RefreshSchedule {
    val ZONE: ZoneId = ZoneId.of("Africa/Casablanca")
    val SLOT_TIMES: List<LocalTime> = listOf(
        LocalTime.of(7, 0), LocalTime.of(10, 0), LocalTime.of(13, 0), LocalTime.of(18, 0),
    )

    fun isRefreshDay(date: LocalDate) = date.dayOfWeek != DayOfWeek.SUNDAY

    fun slotsOn(date: LocalDate, zone: ZoneId = ZONE): List<ZonedDateTime> =
        if (!isRefreshDay(date)) emptyList() else SLOT_TIMES.map { ZonedDateTime.of(date, it, zone) }

    /** Premier créneau strictement après [after]. */
    fun nextSlot(after: Instant, zone: ZoneId = ZONE): ZonedDateTime {
        val startDay = after.atZone(zone).toLocalDate()
        for (offset in 0L..8L) {
            slotsOn(startDay.plusDays(offset), zone).firstOrNull { it.toInstant().isAfter(after) }?.let { return it }
        }
        error("Aucun créneau trouvé") // inatteignable : il y a toujours un créneau dans les 8 jours
    }

    /** Dernier créneau échu (≤ [now]). */
    fun lastSlotAtOrBefore(now: Instant, zone: ZoneId = ZONE): ZonedDateTime {
        val startDay = now.atZone(zone).toLocalDate()
        for (offset in 0L..8L) {
            slotsOn(startDay.minusDays(offset), zone).lastOrNull { !it.toInstant().isAfter(now) }?.let { return it }
        }
        error("Aucun créneau trouvé")
    }
}
