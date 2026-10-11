package com.darkcascade.spendable.engine

import com.darkcascade.spendable.data.Confidence
import com.darkcascade.spendable.data.Direction
import com.darkcascade.spendable.data.RecurringEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class DueItem(
    val name: String,
    val dueDate: LocalDate,
    val amountCents: Long,
    val confidence: Confidence,
    /** A matching charge already shows up in the imported data (posted or pending). */
    val alreadyPaid: Boolean,
)

data class Estimate(
    val balanceCents: Long,
    /** Balance minus HIGH-confidence charges due before the horizon. */
    val highCents: Long,
    /** [highCents] minus MEDIUM and LOW confidence charges too. */
    val lowCents: Long,
    val today: LocalDate,
    /** Exclusive end of the window: the next payday, or the 1st of next month. */
    val horizonEnd: LocalDate,
    val payday: Payday?,
    val items: List<DueItem>,
    /** Days between the newest imported transaction and today; null when nothing is imported. */
    val dataAgeDays: Long?,
)

object DisposableCalculator {

    /** Due dates up to this many days ago still count if the data covers them and nothing posted yet. */
    private const val LATE_GRACE_DAYS = 2L

    /**
     * @param txns every imported row, including pending ones, used to spot charges already taken
     *        this cycle so they aren't subtracted twice.
     */
    fun estimate(
        balanceCents: Long,
        today: LocalDate,
        recurring: List<RecurringEntity>,
        txns: List<Txn>,
        payday: Payday?,
    ): Estimate {
        val horizonEnd = payday?.date ?: YearMonth.from(today).plusMonths(1).atDay(1)
        val windowStart = today.minusDays(LATE_GRACE_DAYS)
        val dataEnd = txns.maxOfOrNull { it.date }

        val items = recurring.filter { it.flow == Direction.EXPENSE }.flatMap { r ->
            dueDates(r.dayOfMonth, windowStart, horizonEnd).mapNotNull { due ->
                // A due date that's already behind us only counts if the data covers it (and it's missing);
                // otherwise it most likely posted after the last import.
                if (due.isBefore(today) && (dataEnd == null || dataEnd.isBefore(due))) return@mapNotNull null
                val paid = txns.any { t ->
                    t.merchantKey == r.merchantKey && t.amountCents < 0 &&
                        abs(ChronoUnit.DAYS.between(due, t.date)) <= 5 &&
                        abs(abs(t.amountCents) - r.expectedAmountCents) <= maxOf(r.expectedAmountCents * 0.3, 200.0)
                }
                DueItem(r.displayName, due, r.expectedAmountCents, r.confidence, paid)
            }
        }.sortedWith(compareBy<DueItem> { it.dueDate }.thenBy { it.confidence })

        val owed = items.filterNot { it.alreadyPaid }
        val high = balanceCents - owed.filter { it.confidence == Confidence.HIGH }.sumOf { it.amountCents }
        val low = high - owed.filter { it.confidence != Confidence.HIGH }.sumOf { it.amountCents }
        return Estimate(
            balanceCents = balanceCents,
            highCents = high,
            lowCents = low,
            today = today,
            horizonEnd = horizonEnd,
            payday = payday,
            items = items,
            dataAgeDays = dataEnd?.let { ChronoUnit.DAYS.between(it, today) },
        )
    }

    /** Occurrences of [day]-of-month in [from, untilExclusive), clamped to short months. */
    internal fun dueDates(day: Int, from: LocalDate, untilExclusive: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var ym = YearMonth.from(from)
        while (!ym.atDay(1).isAfter(untilExclusive)) {
            val d = ym.atDay(minOf(day, ym.lengthOfMonth()))
            if (!d.isBefore(from) && d.isBefore(untilExclusive)) out += d
            ym = ym.plusMonths(1)
        }
        return out
    }
}
