package com.darkcascade.spendable.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Raw transaction as imported from a bank CSV.
 *
 * [id] is a deterministic dedupe key (see `ingest.TransactionKeys`), so re-importing an
 * overlapping date range is an idempotent insert.
 */
@Entity(
    tableName = "transactions",
    indices = [Index("merchantKey"), Index("epochDay")],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val epochDay: Long,
    /** Cleaned merchant name, used for display. */
    val description: String,
    /** The bank's raw descriptor, when the export provides one. */
    val originalDescription: String,
    /** Normalized merchant identifier used for grouping. */
    val merchantKey: String,
    val category: String,
    /** Signed amount in cents: negative = money out, positive = money in. */
    val amountCents: Long,
    /** Pending rows are replaced wholesale on every import and are not used for analysis. */
    val pending: Boolean,
    val importedAtMillis: Long,
)

enum class Confidence { HIGH, MEDIUM, LOW }

enum class Direction { EXPENSE, INCOME }

/** A detected recurring charge or deposit: one merchant, one usual day of month, one amount band. */
@Entity(tableName = "recurring", indices = [Index("dayOfMonth")])
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantKey: String,
    val displayName: String,
    val flow: Direction,
    val dayOfMonth: Int,
    /** Positive expected amount in cents. */
    val expectedAmountCents: Long,
    /** Distinct months with a matching charge. */
    val hits: Int,
    /** Months in which a charge was expected. */
    val expectedMonths: Int,
    val lastSeenEpochDay: Long,
    /** Heuristic engine score, 0..1. */
    val heuristicScore: Double,
    /** Jev noul (probability this is a true recurring bill), when Jev refinement ran. */
    val jevScore: Double?,
    /** Final blended score, 0..1. */
    val score: Double,
    val confidence: Confidence,
    /** Short human-readable explanation of the score. */
    val rationale: String,
)
