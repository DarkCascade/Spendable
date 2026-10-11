package com.darkcascade.spendable

import com.darkcascade.spendable.ingest.CsvParser
import com.darkcascade.spendable.ingest.Merchants
import com.darkcascade.spendable.ingest.TransactionKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvParserTest {

    private val usaa = """
        Date,Description,Original Description,Category,Amount,Status
        2026-03-31,"Winn-Dixie","WINN-DIXIE   #07 11092   SPRING HILL  FL",Groceries,-192.69,Posted
        2026-03-30,"Uber","PAYPAL           INST XFER  ***********UBER",Rental Car & Taxi,-5.00,Posted
        2026-03-30,"Uber","PAYPAL           INST XFER  ***********UBER",Rental Car & Taxi,-5.00,Posted
        2026-03-27,"Payroll","ACME PAYROLL",Paycheck,600.00,Posted
        2026-04-01,"Netflix, Inc.","NETFLIX.COM",Entertainment,-15.49,Pending
    """.trimIndent()

    @Test fun parsesUsaaExport() {
        val r = CsvParser.parse(usaa)
        assertEquals("USAA", r.formatName)
        assertEquals(5, r.transactions.size)
        assertTrue(r.hasCleanMerchantNames)
        val first = r.transactions.first()
        assertEquals(LocalDate.of(2026, 3, 31), first.date)
        assertEquals(-19269L, first.amountCents)
        assertEquals("WINN-DIXIE   #07 11092   SPRING HILL  FL", first.originalDescription)
        assertEquals(60000L, r.transactions[3].amountCents)
        assertEquals("Netflix, Inc.", r.transactions[4].description)
        assertTrue(r.transactions[4].pending)
    }

    @Test fun identicalRowsInOneFileAreKeptButReimportsDedupe() {
        val first = TransactionKeys.assign(CsvParser.parse(usaa).transactions).map { it.first }
        assertEquals(5, first.toSet().size) // the two $5 Ubers both survive
        // An overlapping export containing just the Ubers yields the same two keys.
        val overlap = usaa.lines().filterIndexed { i, _ -> i == 0 || i == 2 || i == 3 }.joinToString("\n")
        val second = TransactionKeys.assign(CsvParser.parse(overlap).transactions).map { it.first }
        assertEquals(setOf(first[1], first[2]), second.toSet())
    }

    @Test fun parsesUsaaLegacyHeaderless() {
        val r = CsvParser.parse("posted,,03/02/2026,,\"NETFLIX.COM\",\"Entertainment\",-15.49\npending,,03/03/2026,,\"SHELL\",\"Gas\",-40.00")
        assertEquals("USAA (legacy)", r.formatName)
        assertEquals(2, r.transactions.size)
        assertEquals(LocalDate.of(2026, 3, 2), r.transactions[0].date)
        assertTrue(r.transactions[1].pending)
    }

    @Test fun parsesDebitCreditColumns() {
        val r = CsvParser.parse("Transaction Date,Posted Date,Card No.,Description,Category,Debit,Credit\n2026-03-01,2026-03-02,1234,SPOTIFY,Music,10.99,\n2026-03-05,2026-03-05,1234,REFUND,Other,,5.00")
        assertEquals(listOf(-1099L, 500L), r.transactions.map { it.amountCents })
    }

    @Test fun amountsAndDates() {
        assertEquals(-123456L, CsvParser.parseCents("($1,234.56)"))
        assertEquals(-1234L, CsvParser.parseCents("12.34-"))
        assertEquals(1000L, CsvParser.parseCents("+10"))
        assertEquals(LocalDate.of(2026, 1, 5), CsvParser.parseDate("1/5/26"))
        assertEquals(LocalDate.of(2026, 3, 2), CsvParser.parseDate("Mar 2, 2026"))
    }

    @Test fun quotedFieldsWithCommasAndQuotes() {
        val rows = CsvParser.readRows("a,\"b, \"\"c\"\"\",d\r\n1,2,3")
        assertEquals(listOf("a", "b, \"c\"", "d"), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    @Test fun merchantKeys() {
        assertEquals("NETFLIX INC", Merchants.key("Netflix, Inc.", isCleanName = true))
        assertEquals("WINN DIXIE SPRING", Merchants.key("WINN-DIXIE   #07 11092   SPRING HILL  FL", isCleanName = false))
        assertEquals(Merchants.key("SQ *BLUE BOTTLE 123", false), Merchants.key("SQ *BLUE BOTTLE 987", false))
        assertNotEquals(Merchants.key("SPOTIFY", false), Merchants.key("NETFLIX", false))
    }
}
