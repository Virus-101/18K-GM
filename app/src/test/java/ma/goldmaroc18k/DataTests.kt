package ma.goldmaroc18k

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import ma.goldmaroc18k.data.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val GOLD_OK = """{"symbols":[{"symbol":"XAU","quote_currency":"USD","unit":"troy_ounce","contract_type":"spot","price":"4131.67","is_stale":false,"computed_at":"2026-10-21T17:00:04.955729+00:00"}]}"""
private const val FX_OK = """{"result":"success","provider":"https://www.exchangerate-api.com","time_last_update_unix":1792540952,"time_next_update_unix":1792628862,"base_code":"USD","rates":{"USD":1,"MAD":9.2}}"""
private val NOW = Instant.parse("2026-10-21T17:10:00Z")

class ParserTest {
    @Test fun `or valide - format reel`() {
        val q = GoldParser.parse(GOLD_OK, NOW)
        assertEquals(0, BigDecimal("4131.67").compareTo(q.priceUsdPerOz))
        assertEquals(Instant.parse("2026-10-21T17:00:04.955729Z"), q.computedAt)
        assertFalse(q.isStale)
    }
    @Test fun `or - json invalide`() { assertThrows(ApiException.Invalid::class.java) { GoldParser.parse("<html>", NOW) } }
    @Test fun `or - champ manquant`() {
        assertThrows(ApiException.Invalid::class.java) { GoldParser.parse("""{"symbols":[]}""", NOW) }
        assertThrows(ApiException.Invalid::class.java) { GoldParser.parse("""{"foo":1}""", NOW) }
    }
    @Test fun `or - prix non numerique, nul, negatif ou aberrant`() {
        listOf("abc", "0", "-5", "9999999").forEach { p ->
            assertThrows(ApiException.Invalid::class.java) { GoldParser.parse(GOLD_OK.replace("4131.67", p), NOW) }
        }
    }
    @Test fun `or - unite ou devise inattendue`() {
        assertThrows(ApiException.Invalid::class.java) { GoldParser.parse(GOLD_OK.replace("troy_ounce", "gram"), NOW) }
        assertThrows(ApiException.Invalid::class.java) { GoldParser.parse(GOLD_OK.replace("\"USD\"", "\"EUR\""), NOW) }
    }
    @Test fun `or - horodatage futur ou illisible`() {
        assertThrows(ApiException.Invalid::class.java) { GoldParser.parse(GOLD_OK.replace("2026-10-21T17:00", "2026-10-22T17:00"), NOW) }
        assertThrows(ApiException.Invalid::class.java) { GoldParser.parse(GOLD_OK.replace("2026-10-21T17:00:04.955729+00:00", "hier"), NOW) }
    }
    @Test fun `or - is_stale vrai conserve`() { assertTrue(GoldParser.parse(GOLD_OK.replace("false", "true"), NOW).isStale) }

    @Test fun `change valide`() {
        val q = FxParser.parse(FX_OK)
        assertEquals(0, BigDecimal("9.2").compareTo(q.usdMad))
        assertEquals(Instant.ofEpochSecond(1792540952), q.updatedAt)
        assertEquals(Instant.ofEpochSecond(1792628862), q.nextUpdateAt)
    }
    @Test fun `change - erreur du fournisseur et quota`() {
        assertThrows(ApiException.Invalid::class.java) { FxParser.parse("""{"result":"error","error-type":"unsupported-code"}""") }
        assertThrows(ApiException.RateLimited::class.java) { FxParser.parse("""{"result":"error","error-type":"quota-reached"}""") }
    }
    @Test fun `change - MAD absent, aberrant, json invalide`() {
        assertThrows(ApiException.Invalid::class.java) { FxParser.parse(FX_OK.replace("\"MAD\":9.2", "\"EUR\":0.9")) }
        assertThrows(ApiException.Invalid::class.java) { FxParser.parse(FX_OK.replace("9.2", "0")) }
        assertThrows(ApiException.Invalid::class.java) { FxParser.parse("pas du json") }
    }
}

class ClientTest {
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.shutdown() }
    private fun base() = server.url("/").toString().trimEnd('/')

    @Test fun `appelle le bon endpoint or et parse`() {
        server.enqueue(MockResponse().setBody(GOLD_OK))
        val q = GoldPriceClient(defaultHttpClient(), base()).fetch()
        assertEquals("/v1/prices?symbol=XAU-USD-SPOT", server.takeRequest().path)
        assertEquals(0, BigDecimal("4131.67").compareTo(q.priceUsdPerOz))
    }
    @Test fun `appelle le bon endpoint change`() {
        server.enqueue(MockResponse().setBody(FX_OK))
        ExchangeRateClient(defaultHttpClient(), base()).fetch()
        assertEquals("/v6/latest/USD", server.takeRequest().path)
    }
    @Test fun `quota depasse 429 avec Retry-After`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val e = assertThrows(ApiException.RateLimited::class.java) { GoldPriceClient(defaultHttpClient(), base()).fetch() }
        assertEquals(120L, e.retryAfterSec)
    }
    @Test fun `erreur serveur 500`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(500, assertThrows(ApiException.Http::class.java) { ExchangeRateClient(defaultHttpClient(), base()).fetch() }.code)
    }
    @Test fun `erreur reseau`() {
        val url = base(); server.shutdown()
        assertThrows(ApiException.Network::class.java) { GoldPriceClient(defaultHttpClient(), url).fetch() }
    }
}

/** DAO en mémoire : permet de tester le dépôt (intégration logique) sans Android. */
class FakeDao : PriceDao {
    val golds = mutableListOf<GoldQuoteEntity>(); val fxs = mutableListOf<FxQuoteEntity>(); val hist = mutableListOf<PriceHistoryEntity>()
    private var n = 0L
    override fun latestGold(): Flow<GoldQuoteEntity?> = flowOf(golds.maxByOrNull { it.sourceTsMs })
    override fun latestFx(): Flow<FxQuoteEntity?> = flowOf(fxs.maxByOrNull { it.sourceTsMs })
    override suspend fun latestGoldOnce() = golds.maxByOrNull { it.sourceTsMs }
    override suspend fun latestFxOnce() = fxs.maxByOrNull { it.sourceTsMs }
    override suspend fun insertGold(e: GoldQuoteEntity): Long { golds += e.copy(id = ++n); return n }
    override suspend fun insertFx(e: FxQuoteEntity): Long { fxs += e.copy(id = ++n); return n }
    override suspend fun insertHistory(e: PriceHistoryEntity): Long { hist += e.copy(id = ++n); return n }
    override suspend fun touchGold(id: Long, ms: Long) { golds.replaceAll { if (it.id == id) it.copy(fetchedAtMs = ms) else it } }
    override suspend fun touchFx(id: Long, ms: Long) { fxs.replaceAll { if (it.id == id) it.copy(fetchedAtMs = ms) else it } }
    override fun historySince(since: Long): Flow<List<PriceHistoryEntity>> = flowOf(hist.filter { it.fetchedAtMs >= since })
}

class FakeState : SyncState {
    override var lastGoldAttemptMs = 0L; override var lastFxAttemptMs = 0L
    override var lastGoldOk: Boolean? = null; override var lastFxOk: Boolean? = null
    override var goldBlockedUntilMs = 0L; override var fxBlockedUntilMs = 0L
    override var lastError: String? = null
}

class RepositoryTest {
    private val goldQ = GoldQuote(BigDecimal("4131.67"), Instant.parse("2026-10-21T17:00:04Z"), false)
    private val fxQ = FxQuote(BigDecimal("9.2"), Instant.ofEpochSecond(1792540952), Instant.ofEpochSecond(1792628862))
    private fun clock(t: Instant = NOW) = Clock.fixed(t, ZoneOffset.UTC)
    private fun repo(dao: FakeDao, st: FakeState, gold: () -> GoldQuote, fx: () -> FxQuote, backend: (() -> BackendSnapshot)? = null, c: Clock = clock()) =
        PriceRepository(dao, gold, fx, backend, st, c)

    @Test fun `succes des deux sources enregistre un point d'historique correct`() = runBlocking {
        val dao = FakeDao(); val st = FakeState()
        val r = repo(dao, st, { goldQ }, { fxQ }).refresh(Trigger.SCHEDULED)
        assertTrue(r.fullSuccess); assertTrue(r.recorded)
        val h = dao.hist.single()
        val expected24 = 4131.67 * 9.2 / 31.1034768
        assertEquals(expected24, h.price24k.toDouble(), 1e-9)
        assertEquals(expected24 * 0.75, h.price18k.toDouble(), 1e-9)
        assertEquals(goldQ.computedAt.toEpochMilli(), h.goldSourceTsMs); assertEquals(fxQ.updatedAt.toEpochMilli(), h.fxSourceTsMs)
        assertEquals(NOW.toEpochMilli(), h.fetchedAtMs)
        assertEquals(true, st.lastGoldOk); assertEquals(true, st.lastFxOk)
    }

    @Test fun `echec de l'or seul - pas de succes, pas d'historique, derniere valeur fx conservee`() = runBlocking {
        val dao = FakeDao(); val st = FakeState()
        val r = repo(dao, st, { throw ApiException.Network(java.io.IOException("hors ligne")) }, { fxQ }).refresh(Trigger.SCHEDULED)
        assertFalse(r.fullSuccess); assertTrue(r.partial); assertFalse(r.recorded)
        assertTrue(dao.hist.isEmpty()); assertEquals(1, dao.fxs.size); assertTrue(dao.golds.isEmpty())
        assertEquals(false, st.lastGoldOk); assertEquals(true, st.lastFxOk)
    }

    @Test fun `echec du change seul - l'or est conserve separement`() = runBlocking {
        val dao = FakeDao(); val st = FakeState()
        val r = repo(dao, st, { goldQ }, { throw ApiException.Http(503) }).refresh(Trigger.SCHEDULED)
        assertTrue(r.partial); assertFalse(r.recorded); assertEquals(1, dao.golds.size); assertTrue(dao.fxs.isEmpty())
    }

    @Test fun `panne totale conserve les dernieres donnees valides`() = runBlocking {
        val dao = FakeDao(); val st = FakeState()
        repo(dao, st, { goldQ }, { fxQ }).refresh(Trigger.SCHEDULED)
        val r = repo(dao, st, { throw ApiException.Network(java.io.IOException("x")) }, { throw ApiException.Network(java.io.IOException("x")) },
            c = clock(NOW.plusSeconds(3 * 3600))).refresh(Trigger.SCHEDULED)
        assertFalse(r.goldOk); assertFalse(r.fxOk)
        assertEquals(1, dao.golds.size); assertEquals(1, dao.fxs.size); assertEquals(1, dao.hist.size)
    }

    @Test fun `taux inchange n'est pas reinsere - pas d'actualisation artificielle`() = runBlocking {
        val dao = FakeDao(); val st = FakeState()
        repo(dao, st, { goldQ }, { fxQ }).refresh(Trigger.SCHEDULED)
        repo(dao, st, { goldQ.copy(computedAt = goldQ.computedAt.plusSeconds(3 * 3600)) }, { fxQ }, c = clock(NOW.plusSeconds(3 * 3600))).refresh(Trigger.SCHEDULED)
        assertEquals(1, dao.fxs.size); assertEquals(2, dao.golds.size); assertEquals(2, dao.hist.size)
        assertEquals(NOW.plusSeconds(3 * 3600).toEpochMilli(), dao.fxs.single().fetchedAtMs) // « vérifié à »
    }

    @Test fun `quota 429 - backoff respecte le Retry-After puis reprise`() = runBlocking {
        val dao = FakeDao(); val st = FakeState(); var goldCalls = 0
        val r1 = repo(dao, st, { goldCalls++; throw ApiException.RateLimited(600) }, { fxQ }).refresh(Trigger.SCHEDULED)
        assertFalse(r1.goldOk); assertEquals(1, goldCalls)
        assertEquals(NOW.toEpochMilli() + 600_000, st.goldBlockedUntilMs)
        // 5 minutes plus tard : aucun appel (backoff actif)
        repo(dao, st, { goldCalls++; goldQ }, { fxQ }, c = clock(NOW.plusSeconds(300))).refresh(Trigger.SCHEDULED)
        assertEquals(1, goldCalls)
        // 11 minutes plus tard : l'appel reprend
        val r3 = repo(dao, st, { goldCalls++; goldQ }, { fxQ }, c = clock(NOW.plusSeconds(660))).refresh(Trigger.SCHEDULED)
        assertEquals(2, goldCalls); assertTrue(r3.fullSuccess)
    }

    @Test fun `actualisation manuelle limitee - pas deux appels or en moins de 30 s`() = runBlocking {
        val dao = FakeDao(); val st = FakeState(); var goldCalls = 0; var fxCalls = 0
        val g = { goldCalls++; goldQ }; val f = { fxCalls++; fxQ }
        repo(dao, st, g, f).refresh(Trigger.MANUAL)
        val r = repo(dao, st, g, f, c = clock(NOW.plusSeconds(10))).refresh(Trigger.MANUAL)
        assertEquals(1, goldCalls); assertEquals(1, fxCalls)
        assertTrue(r.goldSkipped && r.fxSkipped)
        repo(dao, st, g, f, c = clock(NOW.plusSeconds(60))).refresh(Trigger.MANUAL)
        assertEquals(2, goldCalls); assertEquals(1, fxCalls) // le change reste limité à 1/h en manuel
    }

    @Test fun `reponse invalide - rien n'est enregistre`() = runBlocking {
        val dao = FakeDao(); val st = FakeState()
        val r = repo(dao, st, { GoldParser.parse("{}", NOW) }, { fxQ }).refresh(Trigger.SCHEDULED)
        assertFalse(r.goldOk); assertTrue(dao.golds.isEmpty()); assertTrue(r.goldError!!.contains("invalide"))
    }

    @Test fun `backend disponible - aucun appel direct`() = runBlocking {
        val dao = FakeDao(); val st = FakeState(); var direct = 0
        val r = repo(dao, st, { direct++; goldQ }, { direct++; fxQ }, backend = { BackendSnapshot(goldQ, fxQ) }).refresh(Trigger.SCHEDULED)
        assertTrue(r.fullSuccess); assertEquals(0, direct)
    }

    @Test fun `backend en panne - repli sur les appels directs`() = runBlocking {
        val dao = FakeDao(); val st = FakeState()
        val r = repo(dao, st, { goldQ }, { fxQ }, backend = { throw ApiException.Http(502) }).refresh(Trigger.SCHEDULED)
        assertTrue(r.fullSuccess)
    }
}

class UpdateCheckerTest {
    @Test fun `comparaison de versions`() {
        assertTrue(VersionCompare.isNewer("beta-1.2.0", "1.1.0"))
        assertTrue(VersionCompare.isNewer("v1.10.0", "1.9.9"))
        assertFalse(VersionCompare.isNewer("beta-1.1.0", "1.1.0"))
        assertFalse(VersionCompare.isNewer("beta-1.0.9", "1.1.0"))
        assertFalse(VersionCompare.isNewer("nightly", "1.1.0"))
    }
    private val json = """[{"tag_name":"beta-1.0.0","html_url":"https://x/1","draft":false,"assets":[]},
        {"tag_name":"beta-1.2.0","html_url":"https://x/2","draft":false,"assets":[{"browser_download_url":"https://x/app.apk"}]},
        {"tag_name":"beta-9.9.9","html_url":"https://x/draft","draft":true,"assets":[]}]"""
    @Test fun `trouve la plus recente non brouillon avec apk`() {
        val u = UpdateChecker.parse(json, "1.1.0")!!
        assertEquals("beta-1.2.0", u.version); assertEquals("https://x/app.apk", u.apkUrl)
    }
    @Test fun `aucune mise a jour si a jour, json invalide ou null`() {
        assertNull(UpdateChecker.parse(json, "1.2.0")); assertNull(UpdateChecker.parse("pas du json", "1.0.0")); assertNull(UpdateChecker.parse(null, "1.0.0"))
    }
}
