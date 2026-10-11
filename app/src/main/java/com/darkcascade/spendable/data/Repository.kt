package com.darkcascade.spendable.data

import android.content.Context
import android.net.Uri
import com.darkcascade.spendable.engine.DisposableCalculator
import com.darkcascade.spendable.engine.Estimate
import com.darkcascade.spendable.engine.PaydayPredictor
import com.darkcascade.spendable.engine.RecurrenceEngine
import com.darkcascade.spendable.engine.Txn
import com.darkcascade.spendable.ingest.CsvParser
import com.darkcascade.spendable.ingest.Merchants
import com.darkcascade.spendable.ingest.TransactionKeys
import com.darkcascade.spendable.jev.JevClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class ImportSummary(
    val format: String,
    val rowsInFile: Int,
    val added: Int,
    val duplicates: Int,
    val skippedRows: Int,
    val analysis: AnalysisSummary,
)

data class AnalysisSummary(
    val high: Int,
    val medium: Int,
    val low: Int,
    val income: Int,
    /** Null when Jev wasn't used; otherwise how many candidates Jev scored. */
    val jevScored: Int?,
    val jevError: String?,
)

class Repository(private val context: Context) {
    private val txDao: TransactionDao = Databases.transactions(context).transactionDao()
    private val recDao: RecurringDao = Databases.recurring(context).recurringDao()
    val settings = SettingsStore(context)

    val transactionCount: Flow<Int> = txDao.observeCount()
    val firstDay: Flow<Long?> = txDao.observeFirstDay()
    val lastDay: Flow<Long?> = txDao.observeLastDay()
    val recurring: Flow<List<RecurringEntity>> = recDao.observeAll()
    fun recentTransactions(limit: Int = 200): Flow<List<TransactionEntity>> = txDao.observeRecent(limit)

    suspend fun importCsv(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Couldn't open that file.")
        val parsed = CsvParser.parse(text)
        val now = System.currentTimeMillis()
        val rows = TransactionKeys.assign(parsed.transactions).map { (id, t) ->
            TransactionEntity(
                id = id,
                epochDay = t.date.toEpochDay(),
                description = t.description,
                originalDescription = t.originalDescription,
                merchantKey = Merchants.key(t.description, parsed.hasCleanMerchantNames),
                category = t.category,
                amountCents = t.amountCents,
                pending = t.pending,
                importedAtMillis = now,
            )
        }
        // Pending rows change shape when they post, so they're replaced by each new import rather than deduped.
        txDao.deletePending()
        val added = txDao.insertAll(rows).count { it != -1L }
        ImportSummary(
            format = parsed.formatName,
            rowsInFile = rows.size,
            added = added,
            duplicates = rows.size - added,
            skippedRows = parsed.skippedRows,
            analysis = reanalyze(),
        )
    }

    /** Rebuilds the recurring database from every posted transaction, optionally refined by Jev. */
    suspend fun reanalyze(): AnalysisSummary = withContext(Dispatchers.Default) {
        val posted = txDao.all().filterNot { it.pending }.map { it.toTxn() }
        val candidates = RecurrenceEngine.analyze(posted)

        val s = settings.current()
        var jevScores: List<Double?> = List(candidates.size) { null }
        var jevError: String? = null
        var jevScored: Int? = null
        if (s.jevEnabled && s.jevApiKey.isNotBlank()) {
            val expenseIdx = candidates.indices.filter { candidates[it].flow == Direction.EXPENSE }.take(60)
            try {
                val scores = JevClient(s.jevApiKey).scoreRecurring(expenseIdx.map { candidates[it] })
                jevScores = jevScores.toMutableList().also { m -> expenseIdx.forEachIndexed { i, idx -> m[idx] = scores[i] } }
                jevScored = scores.count { it != null }
            } catch (e: Exception) {
                jevError = e.message
            }
        }

        val rows = candidates.mapIndexedNotNull { i, c ->
            val jev = jevScores[i]
            val blended = if (jev == null) c.heuristicScore else 0.6 * c.heuristicScore + 0.4 * jev
            // Jev may promote or demote; if it pushes a candidate below LOW it's dropped as a coincidence.
            val level = RecurrenceEngine.levelFor(blended, c.hits) ?: return@mapIndexedNotNull null
            RecurringEntity(
                merchantKey = c.merchantKey,
                displayName = c.displayName,
                flow = c.flow,
                dayOfMonth = c.dayOfMonth,
                expectedAmountCents = c.expectedAmountCents,
                hits = c.hits,
                expectedMonths = c.expectedMonths,
                lastSeenEpochDay = c.lastSeen.toEpochDay(),
                heuristicScore = c.heuristicScore,
                jevScore = jev,
                score = blended,
                confidence = level,
                rationale = c.rationale + (jev?.let { " · Jev says %.0f%% recurring".format(it * 100) } ?: ""),
            )
        }
        recDao.replaceAll(rows)
        val expenses = rows.filter { it.flow == Direction.EXPENSE }
        AnalysisSummary(
            high = expenses.count { it.confidence == Confidence.HIGH },
            medium = expenses.count { it.confidence == Confidence.MEDIUM },
            low = expenses.count { it.confidence == Confidence.LOW },
            income = rows.count { it.flow == Direction.INCOME },
            jevScored = jevScored,
            jevError = jevError,
        )
    }

    suspend fun estimate(balanceCents: Long, today: LocalDate = LocalDate.now()): Estimate = withContext(Dispatchers.Default) {
        val all = txDao.all()
        val txns = all.map { it.toTxn() }
        val recurring = recDao.all()
        val payday = PaydayPredictor.next(all.filterNot { it.pending }.map { it.toTxn() }, recurring, today)
        DisposableCalculator.estimate(balanceCents, today, recurring, txns, payday)
    }

    suspend fun testJev(apiKey: String): Double = JevClient(apiKey).ping()

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        txDao.clear()
        recDao.clear()
    }

    private fun TransactionEntity.toTxn() = Txn(
        date = LocalDate.ofEpochDay(epochDay),
        merchantKey = merchantKey,
        displayName = description,
        rawDescription = originalDescription,
        category = category,
        amountCents = amountCents,
    )
}
