package com.darkcascade.spendable

import com.darkcascade.spendable.data.Confidence
import com.darkcascade.spendable.data.Direction
import com.darkcascade.spendable.data.RecurringEntity
import com.darkcascade.spendable.engine.Candidate
import com.darkcascade.spendable.engine.DisposableCalculator
import com.darkcascade.spendable.engine.PaydayPredictor
import com.darkcascade.spendable.engine.RecurrenceEngine
import com.darkcascade.spendable.engine.Txn
import com.darkcascade.spendable.ingest.CsvParser
import com.darkcascade.spendable.ingest.Merchants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class EngineTest {

    private fun t(date: String, name: String, cents: Long) =
        Txn(LocalDate.parse(date), name.uppercase(), name, name, "", cents)

    private fun monthly(name: String, day: Int, cents: Long, months: IntRange = 1..3) =
        months.map { m -> t("2026-%02d-%02d".format(m, day), name, cents) }

    private fun List<Candidate>.find(name: String) = firstOrNull { it.displayName == name }

    @Test fun steadyMonthlyChargeIsHigh() {
        val c = RecurrenceEngine.analyze(monthly("Netflix", 15, -1549), LocalDate.of(2026, 3, 31)).find("Netflix")!!
        assertEquals(Confidence.HIGH, c.confidence)
        assertEquals(15, c.dayOfMonth)
        assertEquals(1549L, c.expectedAmountCents)
    }

    @Test fun twoMonthsCapsAtMedium() {
        val c = RecurrenceEngine.analyze(monthly("Apple", 24, -999, 2..3), LocalDate.of(2026, 3, 31)).find("Apple")!!
        assertEquals(Confidence.MEDIUM, c.confidence)
    }

    @Test fun billingDaySlidesAreClustered() {
        val txns = listOf(t("2026-01-20", "ADT", -6586), t("2026-02-22", "ADT", -6586), t("2026-03-20", "ADT", -6586))
        val c = RecurrenceEngine.analyze(txns, LocalDate.of(2026, 3, 31)).find("ADT")!!
        assertEquals(3, c.hits)
        assertTrue(c.confidence != Confidence.LOW)
    }

    @Test fun oneMerchantTwoSubscriptionsSplitByAmount() {
        val txns = monthly("Rentokil", 23, -4737) + monthly("Rentokil", 24, -14933)
        val found = RecurrenceEngine.analyze(txns, LocalDate.of(2026, 3, 31)).filter { it.displayName == "Rentokil" }
        assertEquals(setOf(4737L, 14933L), found.map { it.expectedAmountCents }.toSet())
    }

    @Test fun frequentSameAmountMerchantIsDemoted() {
        // $10 toll reloads on many days, three of which happen to land on the 23rd.
        val reloads = (1..3).flatMap { m -> listOf(2, 6, 11, 17, 23, 28).map { d -> t("2026-%02d-%02d".format(m, d), "SunPass", -1000) } }
        val c = RecurrenceEngine.analyze(reloads, LocalDate.of(2026, 3, 31)).find("SunPass")
        assertTrue("expected LOW or dropped, was ${c?.confidence}", c == null || c.confidence == Confidence.LOW)
    }

    @Test fun varyingAmountsAreNotRecurring() {
        val txns = listOf(t("2026-01-12", "Amazon", -14714), t("2026-02-12", "Amazon", -1129), t("2026-03-12", "Amazon", -6816))
        assertNull(RecurrenceEngine.analyze(txns, LocalDate.of(2026, 3, 31)).find("Amazon"))
    }

    @Test fun biweeklyPaydayIsProjected() {
        val pay = listOf("2026-01-08", "2026-01-22", "2026-02-05", "2026-02-19", "2026-03-05", "2026-03-19").map { t(it, "Webstaurant", 415085) }
        val p = PaydayPredictor.next(pay, emptyList(), LocalDate.of(2026, 3, 25))!!
        assertEquals(LocalDate.of(2026, 4, 2), p.date)
        assertEquals("every 2 weeks", p.cadence)
    }

    private fun rec(name: String, day: Int, cents: Long, level: Confidence) = RecurringEntity(
        merchantKey = name.uppercase(), displayName = name, flow = Direction.EXPENSE, dayOfMonth = day,
        expectedAmountCents = cents, hits = 3, expectedMonths = 3, lastSeenEpochDay = 0, heuristicScore = 0.8,
        jevScore = null, score = 0.8, confidence = level, rationale = "",
    )

    @Test fun estimateRangeUsesConfidenceTiers() {
        val today = LocalDate.of(2026, 4, 10)
        val recurring = listOf(
            rec("Loan", 12, 58432, Confidence.HIGH),
            rec("Gym", 20, 2900, Confidence.MEDIUM),
            rec("Maybe", 14, 1000, Confidence.LOW),
            rec("NextCycle", 25, 99999, Confidence.HIGH), // after payday → excluded
            rec("AlreadyPaid", 10, 4000, Confidence.HIGH),
        )
        val txns = listOf(t("2026-04-10", "AlreadyPaid", -4000))
        val payday = com.darkcascade.spendable.engine.Payday(LocalDate.of(2026, 4, 24), "Job", "every 2 weeks", 100000)
        val e = DisposableCalculator.estimate(200000, today, recurring, txns, payday)
        assertEquals(200000L - 58432L, e.highCents)
        assertEquals(200000L - 58432L - 2900L - 1000L, e.lowCents)
        assertTrue(e.items.single { it.name == "AlreadyPaid" }.alreadyPaid)
        assertTrue(e.items.none { it.name == "NextCycle" })
    }

    @Test fun noPaydayFallsBackToEndOfMonthAndClampsShortMonths() {
        val e = DisposableCalculator.estimate(10000, LocalDate.of(2026, 2, 20), listOf(rec("Rent", 31, 5000, Confidence.HIGH)), emptyList(), null)
        assertEquals(LocalDate.of(2026, 3, 1), e.horizonEnd)
        assertEquals(LocalDate.of(2026, 2, 28), e.items.single().dueDate)
        assertEquals(5000L, e.highCents)
    }

    /** Prints the analysis of the real export in the project root, if present (never committed). */
    @Test fun localSampleSmokeTest() {
        val file = File("../").listFiles { f -> f.extension.equals("csv", true) }?.firstOrNull()
        assumeTrue("no local sample CSV", file != null)
        val parsed = CsvParser.parse(file!!.readText())
        val txns = parsed.transactions.filterNot { it.pending }.map {
            Txn(it.date, Merchants.key(it.description, parsed.hasCleanMerchantNames), it.description, it.originalDescription, it.category, it.amountCents)
        }
        val candidates = RecurrenceEngine.analyze(txns)
        println("== ${file.name}: ${txns.size} txns, format ${parsed.formatName}")
        candidates.forEach { c ->
            println("%-7s %-6s %-40s day %2d  $%9.2f  score %.2f  %s".format(c.flow, c.confidence, c.displayName, c.dayOfMonth, c.expectedAmountCents / 100.0, c.heuristicScore, c.rationale))
        }
        val incomeRows = candidates.filter { it.flow == Direction.INCOME }.map {
            rec(it.displayName, it.dayOfMonth, it.expectedAmountCents, it.confidence).copy(flow = Direction.INCOME, merchantKey = it.merchantKey)
        }
        println("next payday after 2026-03-31: " + PaydayPredictor.next(txns, incomeRows, LocalDate.of(2026, 3, 31)))
    }
}
