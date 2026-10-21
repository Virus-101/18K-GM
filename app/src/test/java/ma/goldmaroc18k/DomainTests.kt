package ma.goldmaroc18k

import ma.goldmaroc18k.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class GoldMathTest {
    @Test fun `formule 18K avec les valeurs reelles verifiees`() {
        // XAU/USD 4131.67 ; USD/MAD exemple 9.30 (valeur d'exemple de test uniquement)
        val p24 = GoldMath.pricePerGram24k(BigDecimal("4131.67"), BigDecimal("9.30"))
        val expected24 = 4131.67 * 9.30 / 31.1034768
        assertEquals(expected24, p24.toDouble(), 1e-9)
        assertEquals(expected24 * 0.75, GoldMath.pricePerGram18k(p24).toDouble(), 1e-9)
    }

    @Test fun `conversion once troy en grammes`() {
        // 1 once à 31,1034768 g : XAU=31.1034768 USD, taux 1 => 1 MAD/g exactement
        val p = GoldMath.pricePerGram24k(BigDecimal("31.1034768"), BigDecimal.ONE)
        assertEquals(0, p.compareTo(BigDecimal.ONE))
    }

    @Test fun `18K egale 75 pour cent et titres theoriques`() {
        val p24 = BigDecimal("100")
        assertEquals(0, BigDecimal("75").compareTo(GoldMath.pricePerGram18k(p24)))
        assertEquals(0, BigDecimal("75").compareTo(GoldMath.pricePerGramKarat(p24, 18)))
        assertEquals(0, BigDecimal("87.5").compareTo(GoldMath.pricePerGramKarat(p24, 21)))
        assertEquals(91.6666, GoldMath.pricePerGramKarat(p24, 22).toDouble(), 1e-3)
    }

    @Test fun `totaux 1 5 10 20 50 100 g`() {
        val perG = BigDecimal("500.25")
        listOf(1, 5, 10, 20, 50, 100).forEach {
            assertEquals(0, perG.multiply(BigDecimal(it)).compareTo(GoldMath.total(perG, it)))
        }
    }

    @Test fun `arrondi uniquement a l'affichage`() {
        val p = GoldMath.pricePerGram24k(BigDecimal("4131.67"), BigDecimal("9.3"))
        assertTrue(p.scale() > 2)
        assertEquals(2, GoldMath.display(p).scale())
    }

    @Test(expected = IllegalArgumentException::class) fun `refuse un cours nul`() {
        GoldMath.pricePerGram24k(BigDecimal.ZERO, BigDecimal("9"))
    }
}

class RefreshScheduleTest {
    private val z = RefreshSchedule.ZONE
    private fun at(date: String, h: Int, m: Int = 0) = ZonedDateTime.of(LocalDate.parse(date), java.time.LocalTime.of(h, m), z).toInstant()

    @Test fun `quatre creneaux par jour ouvre`() {
        // 2026-10-19 = lundi
        val slots = RefreshSchedule.slotsOn(LocalDate.parse("2026-10-19"))
        assertEquals(listOf(7, 10, 13, 18), slots.map { it.hour })
        assertTrue(slots.all { it.minute == 0 })
    }

    @Test fun `six jours lundi a samedi et jamais le dimanche`() {
        val monday = LocalDate.parse("2026-10-19")
        (0..5).forEach { assertEquals(4, RefreshSchedule.slotsOn(monday.plusDays(it.toLong())).size) }
        val sunday = monday.plusDays(6)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertTrue(RefreshSchedule.slotsOn(sunday).isEmpty())
    }

    @Test fun `apres samedi 18h le prochain creneau est lundi 7h`() {
        val next = RefreshSchedule.nextSlot(at("2026-10-24", 18, 0)) // samedi, créneau exact => strictement après
        assertEquals(LocalDate.parse("2026-10-26"), next.toLocalDate())
        assertEquals(7, next.hour)
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
    }

    @Test fun `prochain creneau dans la journee`() {
        assertEquals(10, RefreshSchedule.nextSlot(at("2026-10-21", 7, 0)).hour)
        assertEquals(13, RefreshSchedule.nextSlot(at("2026-10-21", 12, 59)).hour)
        assertEquals(18, RefreshSchedule.nextSlot(at("2026-10-21", 13, 0)).hour)
        assertEquals(7, RefreshSchedule.nextSlot(at("2026-10-21", 18, 1)).hour)
    }

    @Test fun `dernier creneau echu et dimanche`() {
        val sundayNoon = at("2026-10-25", 12, 0)
        val last = RefreshSchedule.lastSlotAtOrBefore(sundayNoon)
        assertEquals(DayOfWeek.SATURDAY, last.dayOfWeek)
        assertEquals(18, last.hour)
    }

    @Test fun `le fuseau est Casablanca et non celui du telephone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val now = Instant.parse("2026-10-21T05:30:00Z") // 06:30 à Casablanca (UTC+1), 14:30 à Tokyo
        val next = RefreshSchedule.nextSlot(now)
        assertEquals("Africa/Casablanca", next.zone.id)
        assertEquals(7, next.hour)
        assertEquals(Instant.parse("2026-10-21T06:00:00Z"), next.toInstant())
        // Un calcul fait avec le fuseau du téléphone donnerait autre chose : on ne l'utilise jamais.
        assertNotEquals(next.toInstant(), RefreshSchedule.nextSlot(now, tokyo).toInstant())
    }

    @Test fun `changement d'heure saisonnier Ramadan 2026 - le decalage UTC change`() {
        // Hors Ramadan : UTC+1 -> 07:00 = 06:00Z. Pendant le Ramadan 2026 (tzdata) : UTC+0 -> 07:00 = 07:00Z.
        val before = RefreshSchedule.slotsOn(LocalDate.parse("2026-02-10")).first().toInstant()
        val during = RefreshSchedule.slotsOn(LocalDate.parse("2026-03-02")).first().toInstant()
        assertEquals(Duration.ofHours(6), Duration.between(LocalDate.parse("2026-02-10").atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), before))
        assertEquals(Duration.ofHours(7), Duration.between(LocalDate.parse("2026-03-02").atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), during))
        // L'heure locale reste 07:00, 10:00, 13:00, 18:00 des deux côtés.
        assertEquals(listOf(7, 10, 13, 18), RefreshSchedule.slotsOn(LocalDate.parse("2026-03-02")).map { it.hour })
    }

    @Test fun `la chaine de creneaux ne saute rien sur 2 semaines`() {
        var cur = at("2026-10-19", 0, 1)
        val seen = mutableListOf<ZonedDateTime>()
        repeat(48) { val n = RefreshSchedule.nextSlot(cur); seen += n; cur = n.toInstant() }
        assertEquals(48, seen.size)
        assertTrue(seen.none { it.dayOfWeek == DayOfWeek.SUNDAY })
        assertEquals(24, seen.take(24).size) // 6 jours x 4
        assertEquals(seen.map { it.toInstant() }, seen.map { it.toInstant() }.sorted())
    }
}

class FreshnessTest {
    private val now = Instant.parse("2026-10-21T10:20:00Z") // mercredi 11:20 Casablanca ; dernier créneau 10:00 local = 09:00Z
    private val slot = Instant.parse("2026-10-21T09:00:00Z")

    private fun eval(goldChecked: Instant?, fx: Instant?, stale: Boolean = false, g: Boolean? = true, f: Boolean? = true) =
        FreshnessEvaluator.evaluate(now, goldChecked, fx, stale, g, f)

    @Test fun `a jour`() = assertEquals(DataStatus.UP_TO_DATE, eval(slot.plusSeconds(60), now.minus(Duration.ofHours(10))).status)
    @Test fun `indisponible sans donnees`() = assertEquals(DataStatus.UNAVAILABLE, eval(null, null).status)
    @Test fun `retardees si creneau manque`() {
        val r = eval(slot.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(10)))
        assertEquals(DataStatus.DELAYED, r.status); assertTrue(r.goldMissedSlot)
    }
    @Test fun `retardees si is_stale`() = assertEquals(DataStatus.DELAYED, eval(slot, now, stale = true).status)
    @Test fun `anciennes si or vieux de plus de 24 h`() = assertEquals(DataStatus.OLD, eval(slot.minus(Duration.ofHours(30)), now).status)
    @Test fun `anciennes si taux de change plus vieux que 36 h`() {
        val r = eval(slot, slot.minus(Duration.ofHours(40)))
        assertEquals(DataStatus.OLD, r.status); assertTrue(r.fxOld)
    }
    @Test fun `non synchronise si une seule source a echoue`() {
        val r = eval(slot, now.minus(Duration.ofHours(5)), g = true, f = false)
        assertTrue(r.unsynchronized); assertEquals(DataStatus.DELAYED, r.status)
    }
    @Test fun `dimanche sans actualisation n'est pas signale ancien`() {
        val sunday = Instant.parse("2026-10-25T15:00:00Z")
        val satSlot = Instant.parse("2026-10-24T17:00:00Z") // samedi 18:00 local (UTC+1)
        val r = FreshnessEvaluator.evaluate(sunday, satSlot.plusSeconds(30), satSlot.minus(Duration.ofHours(17)), false, true, true)
        assertEquals(DataStatus.UP_TO_DATE, r.status)
    }
}

class PriceStatsTest {
    private val h = 3_600_000L
    @Test fun `variation vs precedente et 24h`() {
        val pts = listOf(PricePoint(0, 100.0), PricePoint(12 * h, 105.0), PricePoint(24 * h, 110.0), PricePoint(30 * h, 121.0))
        val c = PriceStats.changes(pts)
        assertEquals(10.0, c.vsPreviousPct!!, 1e-9)           // 110 -> 121
        assertEquals(21.0, c.vs24hPct!!, 1e-9)               // réf : dernier point <= t-24h = point t=0 (100) -> 121
    }
    @Test fun `pas de variation 24h sans point assez ancien`() {
        val c = PriceStats.changes(listOf(PricePoint(0, 100.0), PricePoint(2 * h, 101.0)))
        assertNull(c.vs24hPct); assertNotNull(c.vsPreviousPct)
    }
    @Test fun `liste vide ou un point`() {
        assertNull(PriceStats.changes(emptyList()).vsPreviousPct)
        assertNull(PriceStats.changes(listOf(PricePoint(0, 1.0))).vsPreviousPct)
    }
}

class AlertTest {
    @Test fun `notifie au franchissement puis se tait puis se rearme`() {
        var st = AlertState()
        var (ev, s2) = AlertEvaluator.evaluate(1100.0, 1000.0, null, st); st = s2
        assertEquals(listOf(AlertEvent.ABOVE), ev)
        ev = AlertEvaluator.evaluate(1120.0, 1000.0, null, st).also { st = it.second }.first
        assertTrue(ev.isEmpty())
        ev = AlertEvaluator.evaluate(900.0, 1000.0, null, st).also { st = it.second }.first
        assertTrue(ev.isEmpty()); assertFalse(st.aboveFired)
        ev = AlertEvaluator.evaluate(1001.0, 1000.0, null, st).first
        assertEquals(listOf(AlertEvent.ABOVE), ev)
    }
    @Test fun `seuil bas`() {
        assertEquals(listOf(AlertEvent.BELOW), AlertEvaluator.evaluate(500.0, null, 600.0, AlertState()).first)
        assertTrue(AlertEvaluator.evaluate(700.0, null, 600.0, AlertState()).first.isEmpty())
    }
}
