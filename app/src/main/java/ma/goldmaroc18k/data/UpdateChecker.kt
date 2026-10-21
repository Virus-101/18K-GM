package ma.goldmaroc18k.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(val version: String, val pageUrl: String, val apkUrl: String?)

object VersionCompare {
    private fun parts(v: String): List<Int>? =
        Regex("""\d+(\.\d+)*""").find(v)?.value?.split('.')?.map { it.toInt() }

    /** true si [remote] (ex. "beta-1.2.0") est strictement plus récent que [local] (ex. "1.1.0"). */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote) ?: return false
        val l = parts(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}

/** Cherche la dernière release GitHub (pré-versions incluses). Silencieux en cas d'erreur : renvoie null. */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersion: String,
    private val baseUrl: String = "https://api.github.com",
    private val repo: String = "Virus-101/18K-GM",
) {
    fun check(): UpdateInfo? = try {
        val req = Request.Builder().url("$baseUrl/repos/$repo/releases?per_page=5")
            .header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
        parse(body, currentVersion)
    } catch (e: Exception) {
        null
    }

    companion object {
        fun parse(body: String?, currentVersion: String): UpdateInfo? {
            if (body == null) return null
            val arr = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
            return arr.mapNotNull { it as? JsonObject }
                .filter { (it["draft"] as? JsonPrimitive)?.contentOrNull != "true" }
                .mapNotNull { rel ->
                    val tag = (rel["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val page = (rel["html_url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    if (!VersionCompare.isNewer(tag, currentVersion)) return@mapNotNull null
                    val apk = (rel["assets"] as? JsonArray)?.mapNotNull { a -> (a as? JsonObject)?.get("browser_download_url") as? JsonPrimitive }
                        ?.map { it.content }?.firstOrNull { it.endsWith(".apk") }
                    UpdateInfo(tag, page, apk)
                }
                .maxByOrNull { Regex("""\d+""").findAll(it.version).map { m -> m.value.toInt() }.fold(0L) { acc, n -> acc * 1000 + n } }
        }
    }
}
