package com.darkcascade.spendable.ingest

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class ParsedTransaction(
    val date: LocalDate,
    val description: String,
    val originalDescription: String,
    val category: String,
    /** Signed cents: negative = money out. */
    val amountCents: Long,
    val pending: Boolean,
)

data class ParseResult(
    val transactions: List<ParsedTransaction>,
    val skippedRows: Int,
    val formatName: String,
    /** True when the export has a separate cleaned merchant name next to the raw descriptor (USAA). */
    val hasCleanMerchantNames: Boolean,
)

class CsvFormatException(message: String) : Exception(message)

/**
 * Bank CSV parser. Built around USAA's export
 * (`Date,Description,Original Description,Category,Amount,Status`) and USAA's older headerless
 * layout (`posted,,03/02/2026,,DESC,Category,-12.34`), with header-sniffing fallbacks for the
 * usual `Date / Description / Amount` or `Debit / Credit` layouts other banks use.
 */
object CsvParser {

    fun parse(text: String): ParseResult {
        val rows = readRows(text.removePrefix("﻿")).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) throw CsvFormatException("The file is empty.")

        val headerIndex = rows.take(10).indexOfFirst { looksLikeHeader(it) }
        return if (headerIndex >= 0) {
            parseWithHeader(rows[headerIndex], rows.drop(headerIndex + 1))
        } else {
            parseHeaderless(rows)
        }
    }

    // ---- RFC 4180 reader: quoted fields, escaped quotes, embedded commas and newlines ----

    internal fun readRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { row.add(field.toString()); field.clear() }
                    '\r' -> {}
                    '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows.map { r -> r.map { it.trim() } }
    }

    // ---- Header-based layouts ----

    private fun norm(h: String) = h.lowercase(Locale.US).replace(Regex("[^a-z ]"), "").trim()

    private fun looksLikeHeader(row: List<String>): Boolean {
        val names = row.map(::norm)
        val hasDate = names.any { it.contains("date") }
        val hasMoney = names.any { it in AMOUNT_NAMES || it in DEBIT_NAMES || it in CREDIT_NAMES }
        return hasDate && hasMoney
    }

    private fun parseWithHeader(header: List<String>, body: List<List<String>>): ParseResult {
        val names = header.map(::norm)
        fun col(vararg candidates: String): Int =
            candidates.firstNotNullOfOrNull { c -> names.indexOf(c).takeIf { it >= 0 } } ?: -1

        val dateCol = col("date", "transaction date", "trans date", "posting date", "posted date", "post date")
            .takeIf { it >= 0 } ?: names.indexOfFirst { it.contains("date") }
        val descCol = col("description", "payee", "merchant", "name", "merchant name", "transaction description", "memo")
        val origCol = col("original description", "raw description", "details description")
        val catCol = col("category", "transaction category")
        val statusCol = col("status", "transaction status")
        val amountCol = names.indexOfFirst { it in AMOUNT_NAMES }
        val debitCol = names.indexOfFirst { it in DEBIT_NAMES }
        val creditCol = names.indexOfFirst { it in CREDIT_NAMES }

        if (descCol < 0) throw CsvFormatException("Couldn't find a Description column in: ${header.joinToString()}")
        if (amountCol < 0 && debitCol < 0 && creditCol < 0) {
            throw CsvFormatException("Couldn't find an Amount (or Debit/Credit) column in: ${header.joinToString()}")
        }

        var skipped = 0
        val out = body.mapNotNull { r ->
            fun cell(i: Int) = if (i in r.indices) r[i] else ""
            val date = parseDate(cell(dateCol))
            val amount = when {
                amountCol >= 0 -> parseCents(cell(amountCol))
                else -> {
                    val debit = parseCents(cell(debitCol))
                    val credit = parseCents(cell(creditCol))
                    when {
                        debit != null && debit != 0L -> -kotlin.math.abs(debit)
                        credit != null -> kotlin.math.abs(credit)
                        else -> null
                    }
                }
            }
            val desc = cell(descCol)
            if (date == null || amount == null || desc.isBlank()) { skipped++; return@mapNotNull null }
            ParsedTransaction(
                date = date,
                description = desc,
                originalDescription = cell(origCol).ifBlank { desc },
                category = cell(catCol),
                amountCents = amount,
                pending = cell(statusCol).equals("pending", ignoreCase = true),
            )
        }
        val isUsaa = origCol >= 0 && statusCol >= 0
        return ParseResult(out, skipped, if (isUsaa) "USAA" else "Generic (headers)", hasCleanMerchantNames = origCol >= 0)
    }

    // ---- Headerless layouts ----

    private fun parseHeaderless(rows: List<List<String>>): ParseResult {
        // USAA legacy: status,,date,,description,category,amount
        val usaaLegacy = rows.first().let { r ->
            r.size >= 7 && r[0].lowercase(Locale.US) in setOf("posted", "pending") && parseDate(r[2]) != null
        }
        if (usaaLegacy) {
            var skipped = 0
            val out = rows.mapNotNull { r ->
                val date = r.getOrNull(2)?.let(::parseDate)
                val amount = r.getOrNull(6)?.let(::parseCents)
                val desc = r.getOrNull(4).orEmpty()
                if (r.size < 7 || date == null || amount == null || desc.isBlank()) { skipped++; return@mapNotNull null }
                ParsedTransaction(date, desc, desc, r[5], amount, pending = r[0].equals("pending", true))
            }
            return ParseResult(out, skipped, "USAA (legacy)", hasCleanMerchantNames = false)
        }

        // Generic: first date-looking column, last amount-looking column, longest other text column.
        val sample = rows.first()
        val dateCol = sample.indexOfFirst { parseDate(it) != null }
        val amountCol = sample.indices.lastOrNull { i -> i != dateCol && parseCents(sample[i]) != null }
        val descCol = sample.indices.filter { it != dateCol && it != amountCol }.maxByOrNull { sample[it].length }
        if (dateCol < 0 || amountCol == null || descCol == null) {
            throw CsvFormatException("Unrecognized CSV layout. Expected a header row with Date, Description and Amount columns.")
        }
        var skipped = 0
        val out = rows.mapNotNull { r ->
            val date = r.getOrNull(dateCol)?.let(::parseDate)
            val amount = r.getOrNull(amountCol)?.let(::parseCents)
            val desc = r.getOrNull(descCol).orEmpty()
            if (date == null || amount == null || desc.isBlank()) { skipped++; return@mapNotNull null }
            ParsedTransaction(date, desc, desc, "", amount, pending = false)
        }
        return ParseResult(out, skipped, "Generic (no header)", hasCleanMerchantNames = false)
    }

    // ---- Value parsing ----

    private val DATE_FORMATS = listOf("yyyy-MM-dd", "M/d/yyyy", "M/d/yy", "yyyy/M/d", "M-d-yyyy", "MMM d, yyyy", "d-MMM-yyyy")
        .map { DateTimeFormatter.ofPattern(it, Locale.US) }

    internal fun parseDate(s: String): LocalDate? {
        val t = s.trim().substringBefore('T').substringBefore(' ').ifEmpty { return null }
        for (f in DATE_FORMATS) {
            try { return LocalDate.parse(t, f) } catch (_: DateTimeParseException) {}
        }
        // Formats containing spaces ("Mar 2, 2026") were truncated above; retry on the full string.
        for (f in DATE_FORMATS) {
            try { return LocalDate.parse(s.trim(), f) } catch (_: DateTimeParseException) {}
        }
        return null
    }

    /** "$1,234.56", "-12.34", "(12.34)", "12.34-" → signed cents. */
    internal fun parseCents(s: String): Long? {
        var t = s.trim().replace("$", "").replace(",", "").replace(" ", "")
        if (t.isEmpty()) return null
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) { negative = true; t = t.substring(1, t.length - 1) }
        if (t.endsWith("-")) { negative = !negative; t = t.dropLast(1) }
        if (t.startsWith("+")) t = t.drop(1)
        val value = t.toBigDecimalOrNull() ?: return null
        val cents = value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        return if (negative) -cents else cents
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        if (matches(Regex("-?\\d+(\\.\\d+)?|-?\\.\\d+"))) BigDecimal(this) else null

    private val AMOUNT_NAMES = setOf("amount", "transaction amount", "amount usd")
    private val DEBIT_NAMES = setOf("debit", "debits", "withdrawal", "withdrawals", "debit amount")
    private val CREDIT_NAMES = setOf("credit", "credits", "deposit", "deposits", "credit amount")
}
