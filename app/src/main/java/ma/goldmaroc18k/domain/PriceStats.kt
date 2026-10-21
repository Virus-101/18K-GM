package ma.goldmaroc18k.domain

import java.time.Duration

data class PricePoint(val timeMs: Long, val price18k: Double)

data class PriceChanges(val vsPreviousPct: Double?, val vs24hPct: Double?)

object PriceStats {
    /** [points] triés par date croissante. La variation 24 h n'est calculée que si un point ≥ 24 h existe. */
    fun changes(points: List<PricePoint>): PriceChanges {
        if (points.isEmpty()) return PriceChanges(null, null)
        val last = points.last()
        val prev = points.getOrNull(points.size - 2)
        val target = last.timeMs - Duration.ofHours(24).toMillis()
        val ref = points.lastOrNull { it.timeMs <= target }
        return PriceChanges(prev?.let { pct(it.price18k, last.price18k) }, ref?.let { pct(it.price18k, last.price18k) })
    }

    private fun pct(from: Double, to: Double) = if (from == 0.0) null else (to - from) / from * 100.0
}

/** Seuils d'alerte : notifie au franchissement, pas à chaque actualisation. */
data class AlertState(val aboveFired: Boolean = false, val belowFired: Boolean = false)
enum class AlertEvent { ABOVE, BELOW }

object AlertEvaluator {
    fun evaluate(price: Double, above: Double?, below: Double?, state: AlertState): Pair<List<AlertEvent>, AlertState> {
        val events = mutableListOf<AlertEvent>()
        var aboveFired = state.aboveFired
        var belowFired = state.belowFired
        if (above != null) {
            if (price > above) { if (!aboveFired) events += AlertEvent.ABOVE; aboveFired = true } else aboveFired = false
        } else aboveFired = false
        if (below != null) {
            if (price < below) { if (!belowFired) events += AlertEvent.BELOW; belowFired = true } else belowFired = false
        } else belowFired = false
        return events to AlertState(aboveFired, belowFired)
    }
}
