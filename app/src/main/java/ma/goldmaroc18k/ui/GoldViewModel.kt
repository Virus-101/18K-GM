package ma.goldmaroc18k.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ma.goldmaroc18k.GoldApp
import ma.goldmaroc18k.data.RefreshResult
import ma.goldmaroc18k.data.Trigger
import ma.goldmaroc18k.domain.Freshness
import ma.goldmaroc18k.domain.FreshnessEvaluator
import ma.goldmaroc18k.domain.GoldMath
import ma.goldmaroc18k.domain.PriceChanges
import ma.goldmaroc18k.domain.PricePoint
import ma.goldmaroc18k.domain.PriceStats
import ma.goldmaroc18k.domain.RefreshSchedule
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

enum class Range(val label: String, val duration: Duration) {
    H24("24 h", Duration.ofHours(24)),
    D7("7 j", Duration.ofDays(7)),
    D30("30 j", Duration.ofDays(30)),
    Y1("1 an", Duration.ofDays(365)),
}

data class HomeUi(
    val p24: BigDecimal? = null,
    val p18: BigDecimal? = null,
    val xauUsd: BigDecimal? = null,
    val usdMad: BigDecimal? = null,
    val goldSourceTs: Instant? = null,
    val goldCheckedAt: Instant? = null,
    val fxSourceTs: Instant? = null,
    val fxCheckedAt: Instant? = null,
    val goldIsStale: Boolean = false,
    val freshness: Freshness = Freshness(ma.goldmaroc18k.domain.DataStatus.UNAVAILABLE, false, false, false, false),
    val changes: PriceChanges = PriceChanges(null, null),
    val nextSlot: ZonedDateTime = RefreshSchedule.nextSlot(Instant.now()),
    val range: Range = Range.H24,
    val chart: List<PricePoint> = emptyList(),
    val totalPoints: Int = 0,
    val firstPointMs: Long? = null,
    val refreshing: Boolean = false,
    val lastError: String? = null,
    val lastResult: RefreshResult? = null,
)

class GoldViewModel(app: Application) : AndroidViewModel(app) {
    private val goldApp = app as GoldApp
    private val dao = goldApp.db.dao()
    private val prefs = goldApp.prefs

    private val range = MutableStateFlow(Range.H24)
    private val refreshing = MutableStateFlow(false)
    private val lastResult = MutableStateFlow<RefreshResult?>(null)
    private val ticker = flow { while (true) { emit(Instant.now()); kotlinx.coroutines.delay(30_000) } }
    private val history = dao.historySince(Instant.now().minus(Duration.ofDays(400)).toEpochMilli())

    val ui: StateFlow<HomeUi> = combine(
        dao.latestGold(), dao.latestFx(), history, combine(range, refreshing, lastResult) { a, b, c -> Triple(a, b, c) }, ticker,
    ) { g, f, hist, (rng, busy, res), now ->
        val points = hist.map { PricePoint(it.fetchedAtMs, it.price18k.toDouble()) }
        val since = now.minus(rng.duration).toEpochMilli()
        val p24 = if (g != null && f != null) GoldMath.pricePerGram24k(BigDecimal(g.priceUsdPerOz), BigDecimal(f.usdMad)) else null
        HomeUi(
            p24 = p24, p18 = p24?.let { GoldMath.pricePerGram18k(it) },
            xauUsd = g?.let { BigDecimal(it.priceUsdPerOz) }, usdMad = f?.let { BigDecimal(it.usdMad) },
            goldSourceTs = g?.let { Instant.ofEpochMilli(it.sourceTsMs) }, goldCheckedAt = g?.let { Instant.ofEpochMilli(it.fetchedAtMs) },
            fxSourceTs = f?.let { Instant.ofEpochMilli(it.sourceTsMs) }, fxCheckedAt = f?.let { Instant.ofEpochMilli(it.fetchedAtMs) },
            goldIsStale = g?.isStale ?: false,
            freshness = FreshnessEvaluator.evaluate(
                now, g?.let { Instant.ofEpochMilli(it.fetchedAtMs) }, f?.let { Instant.ofEpochMilli(it.sourceTsMs) },
                g?.isStale ?: false, prefs.lastGoldOk, prefs.lastFxOk,
            ),
            changes = PriceStats.changes(points),
            nextSlot = RefreshSchedule.nextSlot(now),
            range = rng, chart = points.filter { it.timeMs >= since },
            totalPoints = points.size, firstPointMs = points.firstOrNull()?.timeMs,
            refreshing = busy, lastError = prefs.lastError, lastResult = res,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        // Rattrapage au lancement : si le dernier créneau échu n'a pas été honoré, on actualise.
        viewModelScope.launch {
            val now = Instant.now()
            val lastSlot = RefreshSchedule.lastSlotAtOrBefore(now).toInstant()
            val g = dao.latestGoldOnce()
            val f = dao.latestFxOnce()
            val missed = g == null || f == null || Instant.ofEpochMilli(g.fetchedAtMs).isBefore(lastSlot) || prefs.lastGoldOk == false || prefs.lastFxOk == false
            if (missed) runRefresh(Trigger.LAUNCH_CATCHUP)
        }
    }

    fun setRange(r: Range) = range.update { r }

    fun refreshNow() {
        if (refreshing.value) return
        viewModelScope.launch { runRefresh(Trigger.MANUAL) }
    }

    private suspend fun runRefresh(trigger: Trigger) {
        refreshing.value = true
        try {
            val res = goldApp.repository.refresh(trigger)
            lastResult.value = res
            if (res.fullSuccess) ma.goldmaroc18k.work.AlertNotifier.check(goldApp)
        } finally {
            refreshing.value = false
        }
    }
}
