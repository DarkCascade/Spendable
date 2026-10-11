package com.darkcascade.spendable.engine

import com.darkcascade.spendable.data.Confidence
import com.darkcascade.spendable.data.Direction
import com.darkcascade.spendable.data.RecurringEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class Payday(val date: LocalDate, val source: String, val cadence: String, val amountCents: Long)

/**
 * Predicts the next paycheck. Paychecks are often biweekly, which day-of-month clustering can't
 * see, so this checks two things:
 *  1. deposit streams with a steady 1–2 week spacing (projected forward from the last deposit), and
 *  2. monthly / semi-monthly deposits the recurrence engine already found by day of month.
 */
object PaydayPredictor {

    private const val MIN_PAYCHECK_CENTS = 100_00L

    fun next(postedTxns: List<Txn>, recurringIncome: List<RecurringEntity>, today: LocalDate): Payday? {
        val options = mutableListOf<Payday>()
        val dataEnd = postedTxns.maxOfOrNull { it.date } ?: return null

        val periodicSources = mutableSetOf<String>()
        val deposits = postedTxns.filter { it.amountCents >= MIN_PAYCHECK_CENTS }
        for ((key, unsorted) in deposits.groupBy { it.merchantKey }) {
            val stream = unsorted.sortedBy { it.date }
            val dates = stream.map { it.date }.distinct().sorted().takeLast(8)
            if (dates.size < 3) continue
            val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
            val period = RecurrenceEngine.median(gaps.map { it.toDouble() }).toLong()
            if (period !in 6..16) continue
            if (gaps.count { abs(it - period) <= 3 } < gaps.size * 0.75) continue
            // The stream must still have been alive when the data was exported.
            if (ChronoUnit.DAYS.between(dates.last(), dataEnd) > period * 2 + 3) continue

            var next = dates.last().plusDays(period)
            while (!next.isAfter(today)) next = next.plusDays(period)
            val amount = RecurrenceEngine.median(stream.takeLast(4).map { it.amountCents.toDouble() }).toLong()
            options += Payday(next, stream.last().displayName, if (period <= 8) "weekly" else "every 2 weeks", amount)
            periodicSources += key
        }

        for (c in recurringIncome) {
            if (c.flow != Direction.INCOME || c.confidence == Confidence.LOW || c.expectedAmountCents < MIN_PAYCHECK_CENTS) continue
            // A biweekly stream also forms day-of-month clusters; trust the interval projection instead.
            if (c.merchantKey in periodicSources) continue
            options += Payday(nextDayOfMonth(c.dayOfMonth, today), c.displayName, "monthly (day ${c.dayOfMonth})", c.expectedAmountCents)
        }
        return options.minByOrNull { it.date }
    }

    /** First date strictly after [today] that falls on [day] (clamped to short months). */
    fun nextDayOfMonth(day: Int, today: LocalDate): LocalDate {
        var ym = YearMonth.from(today)
        while (true) {
            val d = ym.atDay(minOf(day, ym.lengthOfMonth()))
            if (d.isAfter(today)) return d
            ym = ym.plusMonths(1)
        }
    }
}
