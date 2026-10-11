package com.darkcascade.spendable.ingest

import java.security.MessageDigest
import java.util.Locale

object Merchants {

    private val RAW_NOISE_PREFIXES = listOf(
        "POS PURCHASE", "POS DEBIT", "DEBIT CARD PURCHASE", "CHECKCARD", "PURCHASE AUTHORIZED ON",
        "RECURRING PAYMENT", "ACH DEBIT", "ACH CREDIT", "SQ ", "TST ", "DD ", "PP ", "PAYPAL ",
    )

    /**
     * Merchant id used for grouping. When the bank gives a cleaned merchant name (USAA's
     * `Description`), that name *is* the id. Otherwise the raw descriptor is stripped of store
     * numbers, phone numbers, reference codes and processor prefixes.
     */
    fun key(description: String, isCleanName: Boolean): String {
        val upper = description.uppercase(Locale.US)
        if (isCleanName) return upper.replace(Regex("[^A-Z0-9&' ]"), " ").squash()

        var s = upper.replace(Regex("[^A-Z0-9&' ]"), " ").squash()
        RAW_NOISE_PREFIXES.forEach { p -> if (s.startsWith(p)) s = s.removePrefix(p).trim() }
        val tokens = s.split(' ').filter { tok -> tok.isNotBlank() && tok.none(Char::isDigit) }
        return tokens.take(3).joinToString(" ").ifBlank { upper.squash() }
    }

    private fun String.squash() = trim().replace(Regex("\\s+"), " ")
}

object TransactionKeys {
    /**
     * Deterministic dedupe keys for one import file. Identical rows inside a file are genuinely
     * separate charges (two $5 Uber rides on the same day), so each copy gets an occurrence index:
     * overlapping re-exports produce the same keys and are skipped, while true repeats are kept.
     */
    fun assign(rows: List<ParsedTransaction>): List<Pair<String, ParsedTransaction>> {
        val seen = HashMap<String, Int>()
        return rows.map { t ->
            val base = "${t.date}|${t.amountCents}|${t.originalDescription.uppercase(Locale.US).replace(Regex("\\s+"), " ").trim()}"
            val n = seen.merge(base, 1, Int::plus)!! - 1
            sha1("$base|$n") to t
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
