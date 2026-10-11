package com.darkcascade.spendable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darkcascade.spendable.data.Direction
import com.darkcascade.spendable.data.RecurringEntity
import com.darkcascade.spendable.ui.theme.Display
import com.darkcascade.spendable.ui.theme.Neon
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val rowDate = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Neon.Card.copy(alpha = 0.85f))
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun EmptyState(emoji: String, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(emoji, style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Neon.Muted, textAlign = TextAlign.Center)
    }
}

// ---------------- Recurring ----------------

@Composable
fun RecurringScreen(vm: MainViewModel) {
    val all by vm.recurring.collectAsStateWithLifecycle()
    var flow by rememberSaveable { mutableStateOf(Direction.EXPENSE) }
    val shown = all.filter { it.flow == flow }.sortedWith(compareBy<RecurringEntity> { it.dayOfMonth }.thenByDescending { it.score })

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Direction.entries.forEach { f ->
                FilterChip(
                    selected = flow == f,
                    onClick = { flow = f },
                    label = { Text(if (f == Direction.EXPENSE) "💳 Bills (${all.count { it.flow == f }})" else "💵 Income (${all.count { it.flow == f }})") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Neon.Lime.copy(alpha = 0.2f)),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::reanalyze) { Text("Re-run ↻", color = Neon.Cyan) }
        }
        if (shown.isEmpty()) {
            EmptyState("🔍", "Nothing recurring found yet.\nImport a few months of transactions from the ☰ menu. Three or more months works best.")
        } else {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.id }) { r -> RecurringRow(r) }
            }
        }
    }
}

@Composable
private fun RecurringRow(r: RecurringEntity) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Neon.Violet.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("${r.dayOfMonth}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    ConfidenceChip(r.confidence)
                }
                Text(r.rationale, style = MaterialTheme.typography.bodySmall, color = Neon.Muted)
                Text(
                    "score %.2f".format(r.score) + (r.jevScore?.let { " · heuristic %.2f · Jev %.2f".format(r.heuristicScore, it) } ?: "") +
                        " · last ${LocalDate.ofEpochDay(r.lastSeenEpochDay).format(rowDate)}",
                    style = MaterialTheme.typography.labelSmall, color = Neon.Muted.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                formatCents(r.expectedAmountCents),
                style = MaterialTheme.typography.titleMedium,
                color = if (r.flow == Direction.INCOME) Neon.Lime else Color.White,
            )
        }
    }
}

// ---------------- Transactions ----------------

@Composable
fun TransactionsScreen(vm: MainViewModel) {
    val rows by vm.transactions.collectAsStateWithLifecycle()
    val count by vm.transactionCount.collectAsStateWithLifecycle()
    if (rows.isEmpty()) {
        EmptyState("🧾", "No transactions yet.\n☰ → Import bank CSV")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text(
                if (count > rows.size) "Newest ${rows.size} of $count transactions" else "$count transactions",
                style = MaterialTheme.typography.labelLarge, color = Neon.Muted, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(rows, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.description, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        LocalDate.ofEpochDay(t.epochDay).format(rowDate) + (if (t.category.isNotBlank()) " · ${t.category}" else "") + (if (t.pending) " · ⏳ pending" else ""),
                        style = MaterialTheme.typography.bodySmall, color = Neon.Muted,
                    )
                }
                Text(
                    formatCents(t.amountCents),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    color = if (t.amountCents >= 0) Neon.Lime else Color.White,
                )
            }
        }
    }
}

// ---------------- Jev ----------------

@Composable
fun JevScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(settings.jevEnabled) }
    var key by remember { mutableStateOf(settings.jevApiKey) }
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        enabled = settings.jevEnabled
        key = settings.jevApiKey
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🧠 Jev second opinion", style = MaterialTheme.typography.headlineSmall, color = Neon.Cyan)
                Text(
                    "The built-in engine finds candidates by counting same-merchant charges on the same day each month. " +
                        "With Jev on, each candidate bill is also sent to TypeSafe's Jev classifier, which answers " +
                        "\"is this a true recurring bill?\" with a probability. The final score is 60% heuristic + 40% Jev, " +
                        "so Jev can promote a real bill or demote a coincidence.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Privacy: when on, merchant names, bank descriptors, categories, dates and amounts of candidate bills " +
                        "(never your balance, never all transactions) are sent to api.typesafe.ai.",
                    style = MaterialTheme.typography.bodySmall, color = Neon.Amber,
                )
            }
        }

        Panel {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use Jev during analysis", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled, onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Neon.Lime, checkedThumbColor = Neon.Night),
                    )
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("TypeSafe API key") },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "hide" else "show") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.testJev(key) }, enabled = key.isNotBlank()) { Text("Test key") }
                    Button(
                        onClick = { vm.saveJev(enabled, key) },
                        enabled = !enabled || key.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Neon.Lime, contentColor = Neon.Night),
                    ) { Text("Save & re-run analysis") }
                }
                Text(
                    "Key is stored only in this app's private storage and excluded from backups. Get one from the TypeSafe dashboard (docs.typesafe.ai).",
                    style = MaterialTheme.typography.bodySmall, color = Neon.Muted,
                )
            }
        }
    }
}
