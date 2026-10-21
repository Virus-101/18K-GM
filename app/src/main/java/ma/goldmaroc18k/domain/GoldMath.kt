package ma.goldmaroc18k.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Calculs de prix théoriques. Aucune valeur par défaut : toute entrée absente doit être gérée par l'appelant. */
object GoldMath {
    val GRAMS_PER_TROY_OUNCE: BigDecimal = BigDecimal("31.1034768")
    val PURITY_18K: BigDecimal = BigDecimal("0.75")
    private val MC = MathContext(20, RoundingMode.HALF_EVEN)

    /** Prix 24K en MAD/g = XAU/USD × USD/MAD ÷ 31,1034768 */
    fun pricePerGram24k(xauUsd: BigDecimal, usdMad: BigDecimal): BigDecimal {
        require(xauUsd.signum() > 0) { "XAU/USD doit être > 0" }
        require(usdMad.signum() > 0) { "USD/MAD doit être > 0" }
        return xauUsd.multiply(usdMad).divide(GRAMS_PER_TROY_OUNCE, MC)
    }

    /** Prix 18K en MAD/g = prix 24K × 0,75 */
    fun pricePerGram18k(price24k: BigDecimal): BigDecimal = price24k.multiply(PURITY_18K, MC)

    /** Prix théorique d'un titre quelconque (22K, 21K, ...) = prix 24K × karat/24. */
    fun pricePerGramKarat(price24k: BigDecimal, karat: Int): BigDecimal {
        require(karat in 1..24)
        if (karat == 18) return pricePerGram18k(price24k)
        return price24k.multiply(BigDecimal(karat), MC).divide(BigDecimal(24), MC)
    }

    fun total(pricePerGram: BigDecimal, grams: Int): BigDecimal = pricePerGram.multiply(BigDecimal(grams), MC)

    /** Arrondi réservé à l'affichage. */
    fun display(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)
}
