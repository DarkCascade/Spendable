package com.darkcascade.spendable.engine

import com.darkcascade.spendable.data.Confidence
import com.darkcascade.spendable.data.Direction
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/** Engine-side view of a posted transaction. */
data class Txn(
    val date: LocalDate,
    val merchantKey: String,
    val displayName: String,
    val rawDescription: String,
    val category: String,
    /** Signed cents: negative = money out. */
    val amountCents: Long,
)

data class Candidate(
    val merchantKey: String,
    val displayName: String,
    val flow: Direction,
    val dayOfMonth: Int,
    val expectedAmountCents: Long,
    val hits: Int,
    val expectedMonths: Int,
    val lastSeen: LocalDate,
    val heuristicScore: Double,
    val confidence: Confidence,
    val rationale: String,
    /** One representative charge per month, oldest first. */
    val charges: List<Txn>,
    /** Charges from this merchant with a similar amount over the same period (≥ hits). */
    val similarChargesInSpan: Int,
)

/**
 * Finds recurring charges the simple way: same merchant, same day of the month, month after month.
 *
 * Per merchant, charges are clustered by day of month (±2 days, since billing slides around
 * weekends and short months), then split by amount so one merchant can carry several
 * subscriptions (e.g. a $47 plan on the 23rd and a $149 plan on the 24th). Each group is scored on:
 *  - how many distinct months it hit,
 *  - coverage: hits ÷ months it *should* have hit since it first appeared,
 *  - amount stability, day-of-month tightness, and recency,
 * then discounted when it smells like coincidence:
 *  - the merchant has many similar-amount charges per month (toll auto-reloads every few days),
 *  - only two hits with a gap month between them,
 *  - the bank categorizes it as everyday spending (restaurants, gas, groceries...).
 */
object RecurrenceEngine {

    private const val DAY_WINDOW = 4            // max day-of-month span inside one cluster
    private const val AMOUNT_BAND = 1.3         // amounts within 30% belong to one group
    private const val NORMAL_PER_MONTH = 2.0    // two similar bills a month from one merchant is still normal
    private val EVERYDAY_CATEGORIES = setOf(
        "restaurants", "fast food", "food & dining", "dining", "food & drink", "coffee shops", "groceries",
        "gas", "gas & fuel", "fuel", "alcohol & bars", "rental car & taxi", "convenience stores",
    )
    private const val HIGH = 0.70
    private const val MEDIUM = 0.50
    private const val LOW = 0.30

    fun analyze(txns: List<Txn>, asOf: LocalDate = txns.maxOfOrNull { it.date } ?: LocalDate.now()): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (flow in Direction.entries) {
            val signed = txns.filter { if (flow == Direction.EXPENSE) it.amountCents < 0 else it.amountCents > 0 }
            for ((_, charges) in signed.groupBy { it.merchantKey }) {
                if (charges.size < 2) continue
                for (dayCluster in clusterByDay(charges)) {
                    for (group in splitByAmount(dayCluster)) {
                        score(group, charges, asOf, flow)?.let(out::add)
                    }
                }
            }
        }
        return out.sortedWith(compareBy<Candidate> { it.flow }.thenByDescending { it.heuristicScore })
    }

    fun levelFor(score: Double, hits: Int): Confidence? = when {
        score >= HIGH && hits >= 3 -> Confidence.HIGH
        score >= MEDIUM -> Confidence.MEDIUM
        score >= LOW -> Confidence.LOW
        else -> null
    }

    private fun clusterByDay(charges: List<Txn>): List<List<Txn>> {
        val sorted = charges.sortedBy { it.date.dayOfMonth }
        val clusters = mutableListOf<MutableList<Txn>>()
        for (t in sorted) {
            val current = clusters.lastOrNull()
            if (current != null && t.date.dayOfMonth - current.first().date.dayOfMonth <= DAY_WINDOW) current.add(t)
            else clusters.add(mutableListOf(t))
        }
        return clusters
    }

    private fun splitByAmount(cluster: List<Txn>): List<List<Txn>> {
        val sorted = cluster.sortedBy { abs(it.amountCents) }
        val groups = mutableListOf<MutableList<Txn>>()
        for (t in sorted) {
            val current = groups.lastOrNull()
            val floor = current?.first()?.let { abs(it.amountCents) }
            if (floor != null && abs(t.amountCents) <= floor * AMOUNT_BAND + 100) current.add(t)
            else groups.add(mutableListOf(t))
        }
        return groups
    }

    private fun score(group: List<Txn>, merchantCharges: List<Txn>, asOf: LocalDate, flow: Direction): Candidate? {
        val byMonth = group.groupBy { YearMonth.from(it.date) }
        val hits = byMonth.size
        if (hits < 2) return null

        val groupMedian = median(group.map { abs(it.amountCents).toDouble() })
        // One representative charge per month: the one closest to the group's typical amount.
        val perMonth = byMonth.values
            .map { inMonth -> inMonth.minBy { abs(abs(it.amountCents) - groupMedian) } }
            .sortedBy { it.date }

        val typicalDay = median(perMonth.map { it.date.dayOfMonth.toDouble() }).toInt()
        val firstMonth = YearMonth.from(perMonth.first().date)
        val asOfMonth = YearMonth.from(asOf)
        // Only count the current month as "expected" once its billing day (+2 days grace) has passed.
        val dueThisMonth = minOf(typicalDay, asOfMonth.lengthOfMonth())
        val lastExpected = if (asOf.dayOfMonth >= minOf(dueThisMonth + 2, asOfMonth.lengthOfMonth())) asOfMonth else asOfMonth.minusMonths(1)
        val expectedMonths = maxOf(hits, (ChronoUnit.MONTHS.between(firstMonth, lastExpected) + 1).toInt())
        val coverage = hits.toDouble() / expectedMonths

        val amounts = perMonth.map { abs(it.amountCents).toDouble() }
        val amountMedian = median(amounts)
        val spread = (amounts.max() - amounts.min()) / amountMedian
        val amountScore = (1 - spread / 0.3).coerceIn(0.0, 1.0)

        val days = perMonth.map { it.date.dayOfMonth }
        val tightness = (1 - (days.max() - days.min()) / 5.0).coerceIn(0.0, 1.0)

        val lastSeen = perMonth.last().date
        val sinceLast = ChronoUnit.DAYS.between(lastSeen, asOf)
        val recency = (1 - (sinceLast - 35) / 60.0).coerceIn(0.0, 1.0)

        val countScore = 1 - 0.6.pow(hits - 1)

        val lo = amounts.min() / AMOUNT_BAND
        val hi = amounts.max() * AMOUNT_BAND
        val spanStart = firstMonth.atDay(1)
        val spanEnd = YearMonth.from(lastSeen).atEndOfMonth()
        val similar = merchantCharges.count {
            !it.date.isBefore(spanStart) && !it.date.isAfter(spanEnd) && abs(it.amountCents).toDouble() in lo..hi
        }.coerceAtLeast(hits)
        val spanMonths = ChronoUnit.MONTHS.between(firstMonth, YearMonth.from(lastSeen)) + 1
        val similarPerMonth = similar.toDouble() / spanMonths
        val busyMerchant = if (similarPerMonth <= NORMAL_PER_MONTH) 1.0 else (NORMAL_PER_MONTH / similarPerMonth).pow(0.8)

        val category = group.groupingBy { it.category.lowercase() }.eachCount().maxBy { it.value }.key
        val everyday = category in EVERYDAY_CATEGORIES
        val gappy = hits == 2 && expectedMonths > 2

        val raw = 0.35 * countScore + 0.20 * coverage + 0.25 * amountScore + 0.10 * recency + 0.10 * tightness
        val score = raw * busyMerchant * (if (everyday) 0.8 else 1.0) * (if (gappy) 0.8 else 1.0)
        val level = levelFor(score, hits) ?: return null

        val recent = amounts.takeLast(3)
        val rationale = buildString {
            append("$hits of $expectedMonths months near day $typicalDay")
            append(if (spread <= 0.02) " · same amount" else if (spread <= 0.15) " · steady amount" else " · amount varies")
            if (busyMerchant < 1.0) append(" · merchant charges ~%.0f×/month".format(similarPerMonth))
            if (everyday) append(" · everyday category")
            if (sinceLast > 45) append(" · last seen $sinceLast days ago")
        }
        return Candidate(
            merchantKey = perMonth.last().merchantKey,
            displayName = perMonth.last().displayName,
            flow = flow,
            dayOfMonth = typicalDay,
            expectedAmountCents = median(recent).toLong(),
            hits = hits,
            expectedMonths = expectedMonths,
            lastSeen = lastSeen,
            heuristicScore = score,
            confidence = level,
            rationale = rationale,
            charges = perMonth,
            similarChargesInSpan = similar,
        )
    }

    internal fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
