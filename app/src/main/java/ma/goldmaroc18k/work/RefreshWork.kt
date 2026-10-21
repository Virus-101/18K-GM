package ma.goldmaroc18k.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ma.goldmaroc18k.GoldApp
import ma.goldmaroc18k.R
import ma.goldmaroc18k.data.Trigger
import ma.goldmaroc18k.domain.AlertEvaluator
import ma.goldmaroc18k.domain.AlertEvent
import ma.goldmaroc18k.domain.AlertState
import ma.goldmaroc18k.domain.RefreshSchedule
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Planification hybride, volet local : chaque exécution programme le créneau suivant (heure du Maroc).
 * WorkManager ne garantit PAS l'heure exacte (Doze, économie d'énergie, réseau) : l'exécution peut être différée.
 * Un rattrapage est fait au lancement de l'application.
 */
object RefreshScheduler {
    private const val SLOT_PREFIX = "slot-"
    private const val RETRY_NAME = "retry"
    private const val MAX_RETRIES = 2
    private val RETRY_DELAY = Duration.ofMinutes(15)

    fun ensureScheduled(ctx: Context, now: Instant = Instant.now()) {
        val next = RefreshSchedule.nextSlot(now)
        val delay = Duration.between(now, next.toInstant()).coerceAtLeast(Duration.ZERO)
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(delay)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putInt(RefreshWorker.KEY_ATTEMPT, 0).build())
            .build()
        // Nom unique par créneau : KEEP évite les doublons, et le worker courant (créneau précédent) n'est jamais remplacé.
        WorkManager.getInstance(ctx).enqueueUniqueWork(SLOT_PREFIX + next.toEpochSecond(), ExistingWorkPolicy.KEEP, req)
    }

    fun scheduleRetry(ctx: Context, attempt: Int) {
        if (attempt > MAX_RETRIES) return
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(RETRY_DELAY)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putInt(RefreshWorker.KEY_ATTEMPT, attempt).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("$RETRY_NAME-$attempt", ExistingWorkPolicy.REPLACE, req)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GoldApp
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        // Le créneau suivant est programmé d'abord : un plantage ne casse pas la chaîne.
        RefreshScheduler.ensureScheduled(app)
        val trigger = if (attempt == 0) Trigger.SCHEDULED else Trigger.RETRY
        val result = app.repository.refresh(trigger)
        if (result.fullSuccess) {
            AlertNotifier.check(app)
        } else {
            RefreshScheduler.scheduleRetry(app, attempt + 1) // reprise contrôlée : 2 tentatives max, 15 min d'écart
        }
        return Result.success()
    }

    companion object {
        const val KEY_ATTEMPT = "attempt"
    }
}

object AlertNotifier {
    suspend fun check(app: GoldApp) {
        val prefs = app.prefs
        val dao = app.db.dao()
        val g = dao.latestGoldOnce() ?: return
        val f = dao.latestFxOnce() ?: return
        val p18 = ma.goldmaroc18k.domain.GoldMath.let {
            it.pricePerGram18k(it.pricePerGram24k(BigDecimal(g.priceUsdPerOz), BigDecimal(f.usdMad)))
        }.toDouble()
        val (events, newState) = AlertEvaluator.evaluate(p18, prefs.alertAbove, prefs.alertBelow, AlertState(prefs.aboveFired, prefs.belowFired))
        prefs.aboveFired = newState.aboveFired
        prefs.belowFired = newState.belowFired
        if (events.isEmpty() || !prefs.notificationsEnabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nf = NumberFormat.getNumberInstance(Locale.FRANCE).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
        val ts = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(RefreshSchedule.ZONE).format(Instant.ofEpochMilli(g.sourceTsMs))
        val ageMin = Duration.between(Instant.ofEpochMilli(g.fetchedAtMs), Instant.now()).toMinutes()
        events.forEach { e ->
            val title = app.getString(if (e == AlertEvent.ABOVE) R.string.notif_above else R.string.notif_below)
            val body = app.getString(R.string.notif_body, nf.format(p18), ts, ageMin)
            val n = NotificationCompat.Builder(app, ma.goldmaroc18k.GoldApp.CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(body).setAutoCancel(true).build()
            NotificationManagerCompat.from(app).notify(e.ordinal + 100, n)
        }
    }
}
