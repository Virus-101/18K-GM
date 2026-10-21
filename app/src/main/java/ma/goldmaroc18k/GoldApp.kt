package ma.goldmaroc18k

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.room.Room
import ma.goldmaroc18k.data.AppDatabase
import ma.goldmaroc18k.data.AppPrefs
import ma.goldmaroc18k.data.BackendClient
import ma.goldmaroc18k.data.ExchangeRateClient
import ma.goldmaroc18k.data.GoldPriceClient
import ma.goldmaroc18k.data.PriceRepository
import ma.goldmaroc18k.data.defaultHttpClient
import ma.goldmaroc18k.work.RefreshScheduler

class GoldApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var prefs: AppPrefs
        private set
    lateinit var repository: PriceRepository
        private set
    lateinit var updateChecker: ma.goldmaroc18k.data.UpdateChecker
        private set

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "gold.db").build()
        prefs = AppPrefs(this)
        val http = defaultHttpClient()
        updateChecker = ma.goldmaroc18k.data.UpdateChecker(http, BuildConfig.VERSION_NAME)
        val gold = GoldPriceClient(http)
        val fx = ExchangeRateClient(http)
        val backend = BuildConfig.BACKEND_URL.takeIf { it.isNotBlank() }
            ?.let { BackendClient(http, it, BuildConfig.BACKEND_TOKEN) }
        repository = PriceRepository(db.dao(), gold::fetch, fx::fetch, backend?.let { b -> { b.latest() } }, prefs)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(prefs.language))
        createChannel()
        RefreshScheduler.ensureScheduled(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    companion object {
        const val CHANNEL_ALERTS = "price_alerts"
    }
}
