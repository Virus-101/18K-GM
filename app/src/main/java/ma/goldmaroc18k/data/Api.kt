package ma.goldmaroc18k.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited(val retryAfterSec: Long?) : ApiException("Limite de requêtes atteinte (HTTP 429)")
    class Http(val code: Int) : ApiException("Erreur HTTP $code")
    class Invalid(reason: String) : ApiException("Réponse invalide : $reason")
    class Network(cause: Throwable) : ApiException("Réseau indisponible : ${cause.message}", cause)
}

data class GoldQuote(val priceUsdPerOz: BigDecimal, val computedAt: Instant, val isStale: Boolean)
data class FxQuote(val usdMad: BigDecimal, val updatedAt: Instant, val nextUpdateAt: Instant?)

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

private fun OkHttpClient.getBody(url: String, bearer: String? = null): String {
    val req = Request.Builder().url(url).header("Accept", "application/json")
        .apply { if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer") }.build()
    try {
        newCall(req).execute().use { resp ->
            if (resp.code == 429) throw ApiException.RateLimited(resp.header("Retry-After")?.toLongOrNull())
            if (!resp.isSuccessful) throw ApiException.Http(resp.code)
            return resp.body?.string() ?: throw ApiException.Invalid("corps vide")
        }
    } catch (e: IOException) {
        throw ApiException.Network(e)
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun parseRoot(body: String): JsonObject = try {
    json.parseToJsonElement(body).jsonObject
} catch (e: Exception) {
    throw ApiException.Invalid("JSON illisible")
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** GoldPrice.dev — format réel vérifié : {"symbols":[{symbol,quote_currency,unit,price:"4131.67",is_stale,computed_at}]} */
object GoldParser {
    fun parse(body: String, now: Instant = Instant.now()): GoldQuote {
        val root = parseRoot(body)
        val arr = root["symbols"] as? JsonArray ?: throw ApiException.Invalid("champ 'symbols' absent")
        val item = arr.firstOrNull { (it as? JsonObject)?.str("symbol") == "XAU" } as? JsonObject
            ?: throw ApiException.Invalid("symbole XAU absent")
        if (item.str("quote_currency") != "USD") throw ApiException.Invalid("devise inattendue")
        if (item.str("unit") != "troy_ounce") throw ApiException.Invalid("unité inattendue")
        val price = item.str("price")?.toBigDecimalOrNull() ?: throw ApiException.Invalid("prix absent ou non numérique")
        if (price.signum() <= 0 || price > BigDecimal(100_000)) throw ApiException.Invalid("prix hors plage")
        val computedAt = try {
            OffsetDateTime.parse(item.str("computed_at") ?: throw ApiException.Invalid("computed_at absent")).toInstant()
        } catch (e: DateTimeParseException) {
            throw ApiException.Invalid("computed_at illisible")
        }
        if (computedAt.isAfter(now.plusSeconds(300))) throw ApiException.Invalid("horodatage dans le futur")
        val stale = item.str("is_stale")?.toBooleanStrictOrNull() ?: true
        return GoldQuote(price, computedAt, stale)
    }
}

/** ExchangeRate-API open access — {"result":"success","time_last_update_unix":..,"time_next_update_unix":..,"base_code":"USD","rates":{"MAD":..}} */
object FxParser {
    fun parse(body: String): FxQuote {
        val root = parseRoot(body)
        if (root.str("result") != "success") {
            val type = root.str("error-type")
            if (type == "quota-reached" || type == "rate-limited") throw ApiException.RateLimited(null)
            throw ApiException.Invalid("result=${root.str("result")} ${type ?: ""}".trim())
        }
        if (root.str("base_code") != "USD") throw ApiException.Invalid("base_code inattendu")
        val rates = root["rates"] as? JsonObject ?: throw ApiException.Invalid("champ 'rates' absent")
        val mad = rates.str("MAD")?.toBigDecimalOrNull() ?: throw ApiException.Invalid("taux MAD absent")
        if (mad.signum() <= 0 || mad > BigDecimal(100)) throw ApiException.Invalid("taux MAD hors plage")
        val updated = root.str("time_last_update_unix")?.toLongOrNull() ?: throw ApiException.Invalid("time_last_update_unix absent")
        val next = root.str("time_next_update_unix")?.toLongOrNull()?.takeIf { it > 0 }
        return FxQuote(mad, Instant.ofEpochSecond(updated), next?.let { Instant.ofEpochSecond(it) })
    }
}

class GoldPriceClient(private val http: OkHttpClient, private val baseUrl: String = "https://api.goldprice.dev") {
    fun fetch(): GoldQuote = GoldParser.parse(http.getBody("$baseUrl/v1/prices?symbol=XAU-USD-SPOT"))
}

class ExchangeRateClient(private val http: OkHttpClient, private val baseUrl: String = "https://open.er-api.com") {
    fun fetch(): FxQuote = FxParser.parse(http.getBody("$baseUrl/v6/latest/USD"))
}

/** Instantané publié par le backend (voir backend/). Les valeurs sont re-validées ici. */
data class BackendSnapshot(val gold: GoldQuote?, val fx: FxQuote?)

class BackendClient(private val http: OkHttpClient, private val baseUrl: String, private val token: String) {
    fun latest(): BackendSnapshot {
        val root = parseRoot(http.getBody(baseUrl.trimEnd('/') + "/v1/latest", token))
        val gold = (root["gold"] as? JsonObject)?.let { g ->
            val body = """{"symbols":[{"symbol":"XAU","quote_currency":"USD","unit":"troy_ounce","price":"${g.str("price")}","is_stale":"${g.str("isStale")}","computed_at":"${g.str("computedAt")}"}]}"""
            GoldParser.parse(body)
        }
        val fx = (root["fx"] as? JsonObject)?.let { f ->
            val body = """{"result":"success","base_code":"USD","time_last_update_unix":"${f.str("updatedUnix")}","time_next_update_unix":"${f.str("nextUpdateUnix") ?: 0}","rates":{"MAD":"${f.str("usdMad")}"}}"""
            FxParser.parse(body)
        }
        return BackendSnapshot(gold, fx)
    }
}
