package ma.goldmaroc18k.domain

import java.time.Duration
import java.time.Instant

enum class DataStatus { UP_TO_DATE, DELAYED, OLD, UNAVAILABLE }

data class Freshness(
    val status: DataStatus,
    /** Le fournisseur de l'or publie un cours avec 15 min de retard (accès sans clé). */
    val goldProviderDelayed: Boolean,
    val goldMissedSlot: Boolean,
    val fxOld: Boolean,
    val unsynchronized: Boolean,
)

/**
 * Évalue l'état des données par rapport au dernier créneau échu (et non à l'horloge brute),
 * afin de ne pas signaler « ancien » le dimanche où aucune actualisation n'est prévue.
 */
object FreshnessEvaluator {
    val SLOT_GRACE: Duration = Duration.ofMinutes(30)
    val OLD_AFTER: Duration = Duration.ofHours(24)
    val FX_OLD_AFTER: Duration = Duration.ofHours(36)
    const val PROVIDER_DELAY_MINUTES = 15

    /**
     * @param goldCheckedAt dernière récupération réussie du cours de l'or
     * @param fxSourceUpdate horodatage de mise à jour publié par ExchangeRate-API
     * @param goldIsStale drapeau is_stale renvoyé par GoldPrice.dev
     * @param lastGoldAttemptOk / lastFxAttemptOk résultat de la dernière tentative (null = jamais tentée)
     */
    fun evaluate(
        now: Instant,
        goldCheckedAt: Instant?,
        fxSourceUpdate: Instant?,
        goldIsStale: Boolean,
        lastGoldAttemptOk: Boolean?,
        lastFxAttemptOk: Boolean?,
    ): Freshness {
        if (goldCheckedAt == null || fxSourceUpdate == null) {
            return Freshness(DataStatus.UNAVAILABLE, false, false, false, false)
        }
        val lastSlot = RefreshSchedule.lastSlotAtOrBefore(now).toInstant()
        val goldLag = Duration.between(goldCheckedAt, lastSlot) // > 0 : récupération antérieure au dernier créneau
        val goldMissed = goldLag > SLOT_GRACE
        val goldOld = goldLag > OLD_AFTER
        val fxOld = Duration.between(fxSourceUpdate, lastSlot) > FX_OLD_AFTER
        val unsynced = lastGoldAttemptOk == false || lastFxAttemptOk == false
        val status = when {
            goldOld || fxOld -> DataStatus.OLD
            goldIsStale || goldMissed || unsynced -> DataStatus.DELAYED
            else -> DataStatus.UP_TO_DATE
        }
        return Freshness(status, goldProviderDelayed = true, goldMissedSlot = goldMissed, fxOld = fxOld, unsynchronized = unsynced)
    }
}
