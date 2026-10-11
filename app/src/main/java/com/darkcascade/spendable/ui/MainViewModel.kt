package com.darkcascade.spendable.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkcascade.spendable.data.AppSettings
import com.darkcascade.spendable.data.Repository
import com.darkcascade.spendable.engine.Estimate
import com.darkcascade.spendable.ingest.CsvParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

enum class GoPhase { Idle, Charging, Done }

data class HomeState(
    val balanceText: String = "",
    val phase: GoPhase = GoPhase.Idle,
    val estimate: Estimate? = null,
    /** Bumped on every successful Go to restart the celebration. */
    val celebration: Int = 0,
    val inputError: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    /** Non-null while a long-running job runs; the text is shown in the busy overlay. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** One-shot snackbar messages. */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    val transactionCount = repo.transactionCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val firstDay = repo.firstDay.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lastDay = repo.lastDay.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val recurring = repo.recurring.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val transactions = repo.recentTransactions(400).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val settings = repo.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        viewModelScope.launch {
            val last = repo.settings.current().lastBalance
            if (last.isNotEmpty()) _home.update { it.copy(balanceText = last) }
        }
    }

    fun onBalanceChange(text: String) {
        val cleaned = text.filter { it.isDigit() || it == '.' || it == '-' || it == ',' }
        _home.update { it.copy(balanceText = cleaned, inputError = null, phase = GoPhase.Idle, estimate = null) }
    }

    fun go() {
        val state = _home.value
        if (state.phase == GoPhase.Charging) return
        val cents = CsvParser.parseCents(state.balanceText)
        if (cents == null) {
            _home.update { it.copy(inputError = "Type your available balance first") }
            return
        }
        _home.update { it.copy(phase = GoPhase.Charging, estimate = null, inputError = null) }
        viewModelScope.launch {
            repo.settings.setLastBalance(state.balanceText)
            val started = System.currentTimeMillis()
            val estimate = repo.estimate(cents)
            // Let the charging animation have its moment; the math itself is instant.
            delay((1100 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            _home.update { it.copy(phase = GoPhase.Done, estimate = estimate, celebration = it.celebration + 1) }
        }
    }

    fun importCsv(uri: Uri) = runBusy("Ingesting transactions…") {
        val s = repo.importCsv(uri)
        val a = s.analysis
        buildString {
            append("${s.format}: ${s.added} new, ${s.duplicates} duplicate${if (s.duplicates == 1) "" else "s"} skipped")
            if (s.skippedRows > 0) append(", ${s.skippedRows} unreadable rows")
            append(". Recurring: ${a.high} high · ${a.medium} med · ${a.low} low")
            a.jevScored?.let { append(" (Jev scored $it)") }
            a.jevError?.let { append(". Jev failed: $it") }
        }
    }

    fun reanalyze() = runBusy("Re-running analysis…") {
        val a = repo.reanalyze()
        "Recurring: ${a.high} high · ${a.medium} med · ${a.low} low · ${a.income} income" +
            (a.jevScored?.let { " (Jev scored $it)" } ?: "") + (a.jevError?.let { ". Jev failed: $it" } ?: "")
    }

    fun saveJev(enabled: Boolean, key: String) = runBusy(if (enabled) "Asking Jev about your bills…" else "Re-running analysis…") {
        repo.settings.setJev(enabled, key)
        val a = repo.reanalyze()
        when {
            a.jevError != null -> "Saved, but Jev failed: ${a.jevError}"
            a.jevScored != null -> "Jev scored ${a.jevScored} candidates → ${a.high} high · ${a.medium} med · ${a.low} low"
            else -> "Saved. Heuristic only: ${a.high} high · ${a.medium} med · ${a.low} low"
        }
    }

    fun testJev(key: String) = runBusy("Pinging Jev…") {
        val p = repo.testJev(key.trim())
        "Jev is alive ✔ (Netflix looks %.0f%% recurring to it)".format(p * 100)
    }

    fun clearAll() = runBusy("Wiping data…") {
        repo.clearAll()
        _home.update { it.copy(phase = GoPhase.Idle, estimate = null) }
        "All transactions and recurring data deleted."
    }

    fun consumeMessage() { _messages.value = null }

    private fun runBusy(label: String, block: suspend () -> String) {
        if (_busy.value != null) return
        _busy.value = label
        viewModelScope.launch {
            _messages.value = try {
                block()
            } catch (e: Exception) {
                "⚠️ ${e.message ?: e::class.simpleName}"
            } finally {
                _busy.value = null
            }
        }
    }
}

private val money = NumberFormat.getCurrencyInstance(Locale.US)

fun formatCents(cents: Long): String = money.format(cents / 100.0)
