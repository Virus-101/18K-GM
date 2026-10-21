package ma.goldmaroc18k.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ma.goldmaroc18k.domain.GoldMath
import java.math.BigDecimal
import java.time.Clock

/** État de synchronisation persistant (implémenté par AppPrefs ; substituable dans les tests). */
interface SyncState {
    var lastGoldAttemptMs: Long
    var lastFxAttemptMs: Long
    var lastGoldOk: Boolean?
    var lastFxOk: Boolean?
    var goldBlockedUntilMs: Long
    var fxBlockedUntilMs: Long
    var lastError: String?
}

enum class Trigger { SCHEDULED, RETRY, MANUAL, LAUNCH_CATCHUP }

data class RefreshResult(
    val goldOk: Boolean,
    val fxOk: Boolean,
    val goldError: String? = null,
    val fxError: String? = null,
    /** true si un point d'historique a été enregistré (les DEUX sources valides). */
    val recorded: Boolean = false,
    val goldSkipped: Boolean = false,
    val fxSkipped: Boolean = false,
) {
    val fullSuccess get() = goldOk && fxOk
    val partial get() = goldOk != fxOk
}

class PriceRepository(
    private val dao: PriceDao,
    private val fetchGold: () -> GoldQuote,
    private val fetchFx: () -> FxQuote,
    private val fetchBackend: (() -> BackendSnapshot)?,
    private val state: SyncState,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        /** GoldPrice.dev sans clé : 10 requêtes/min et 100/h par IP → au plus 1 requête / 30 s en manuel. */
        const val MANUAL_GOLD_MIN_INTERVAL_MS = 30_000L
        /** ExchangeRate-API : mise à jour quotidienne ; « 1 requête/heure ne sera jamais limitée ». */
        const val MANUAL_FX_MIN_INTERVAL_MS = 3_600_000L
        const val DEFAULT_BACKOFF_MS = 20 * 60_000L
    }

    suspend fun refresh(trigger: Trigger): RefreshResult = withContext(Dispatchers.IO) {
        val now = clock.millis()
        val manual = trigger == Trigger.MANUAL
        var goldFresh: GoldQuote? = null
        var fxFresh: FxQuote? = null

        // 1) Backend (créneaux exacts côté serveur) si configuré ; sinon/échec → appels directs.
        if (fetchBackend != null) {
            runCatching { fetchBackend.invoke() }.onSuccess { snap ->
                goldFresh = snap.gold
                fxFresh = snap.fx
            }
        }

        // 2) Appels directs pour ce qui manque, en respectant limites et backoff.
        var goldError: String? = null
        var fxError: String? = null
        var goldSkipped = false
        var fxSkipped = false

        if (goldFresh == null) {
            when {
                now < state.goldBlockedUntilMs -> { goldError = "Limite de requêtes : nouvelle tentative autorisée plus tard"; goldSkipped = true }
                manual && now - state.lastGoldAttemptMs < MANUAL_GOLD_MIN_INTERVAL_MS -> { goldSkipped = true }
                else -> {
                    state.lastGoldAttemptMs = now
                    try { goldFresh = fetchGold() } catch (e: ApiException) {
                        goldError = e.message
                        if (e is ApiException.RateLimited) state.goldBlockedUntilMs = now + (e.retryAfterSec?.times(1000) ?: DEFAULT_BACKOFF_MS)
                    }
                }
            }
        }
        if (fxFresh == null) {
            when {
                now < state.fxBlockedUntilMs -> { fxError = "Limite de requêtes : nouvelle tentative autorisée plus tard"; fxSkipped = true }
                manual && now - state.lastFxAttemptMs < MANUAL_FX_MIN_INTERVAL_MS && dao.latestFxOnce() != null -> { fxSkipped = true }
                else -> {
                    state.lastFxAttemptMs = now
                    try { fxFresh = fetchFx() } catch (e: ApiException) {
                        fxError = e.message
                        if (e is ApiException.RateLimited) state.fxBlockedUntilMs = now + (e.retryAfterSec?.times(1000) ?: DEFAULT_BACKOFF_MS)
                    }
                }
            }
        }

        // 3) Persistance séparée par source (les dernières valeurs valides sont conservées).
        goldFresh?.let { storeGold(it, now) }
        fxFresh?.let { storeFx(it, now) }

        // Une source « ignorée » (anti-spam) n'est pas un échec tant qu'une valeur valide récente existe.
        val goldOk = goldFresh != null || (goldSkipped && goldError == null && dao.latestGoldOnce() != null)
        val fxOk = fxFresh != null || (fxSkipped && fxError == null && dao.latestFxOnce() != null)
        if (goldFresh != null) state.lastGoldOk = true else if (!goldSkipped || goldError != null) state.lastGoldOk = false
        if (fxFresh != null) state.lastFxOk = true else if (!fxSkipped || fxError != null) state.lastFxOk = false

        // 4) Un point d'historique n'est enregistré que si les deux sources sont valides.
        var recorded = false
        if (goldOk && fxOk && (goldFresh != null || fxFresh != null)) {
            val g = dao.latestGoldOnce()
            val f = dao.latestFxOnce()
            if (g != null && f != null) {
                val p24 = GoldMath.pricePerGram24k(BigDecimal(g.priceUsdPerOz), BigDecimal(f.usdMad))
                val p18 = GoldMath.pricePerGram18k(p24)
                dao.insertHistory(
                    PriceHistoryEntity(
                        fetchedAtMs = now, goldSourceTsMs = g.sourceTsMs, fxSourceTsMs = f.sourceTsMs,
                        xauUsd = g.priceUsdPerOz, usdMad = f.usdMad,
                        price24k = p24.toPlainString(), price18k = p18.toPlainString(),
                    ),
                )
                recorded = true
            }
        }
        state.lastError = listOfNotNull(goldError?.let { "Or : $it" }, fxError?.let { "Change : $it" }).joinToString(" · ").ifEmpty { null }
        RefreshResult(goldOk, fxOk, goldError, fxError, recorded, goldSkipped, fxSkipped)
    }

    private suspend fun storeGold(q: GoldQuote, now: Long) {
        val last = dao.latestGoldOnce()
        if (last != null && last.sourceTsMs == q.computedAt.toEpochMilli() && last.priceUsdPerOz == q.priceUsdPerOz.toPlainString()) {
            dao.touchGold(last.id, now)
        } else {
            dao.insertGold(GoldQuoteEntity(fetchedAtMs = now, sourceTsMs = q.computedAt.toEpochMilli(), priceUsdPerOz = q.priceUsdPerOz.toPlainString(), isStale = q.isStale))
        }
    }

    /** Le taux n'est inséré que si le fournisseur a publié une nouvelle valeur (pas d'actualisation artificielle). */
    private suspend fun storeFx(q: FxQuote, now: Long) {
        val last = dao.latestFxOnce()
        if (last != null && last.sourceTsMs == q.updatedAt.toEpochMilli()) {
            dao.touchFx(last.id, now)
        } else {
            dao.insertFx(FxQuoteEntity(fetchedAtMs = now, sourceTsMs = q.updatedAt.toEpochMilli(), nextUpdateMs = q.nextUpdateAt?.toEpochMilli(), usdMad = q.usdMad.toPlainString()))
        }
    }
}
