package com.darkcascade.spendable.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.darkcascade.spendable.data.Confidence
import com.darkcascade.spendable.engine.DueItem
import com.darkcascade.spendable.engine.Estimate
import com.darkcascade.spendable.ui.theme.Display
import com.darkcascade.spendable.ui.theme.Neon
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val shortDate = DateTimeFormatter.ofPattern("EEE MMM d")
private val tinyDate = DateTimeFormatter.ofPattern("MMM d")

@Composable
fun HomeScreen(vm: MainViewModel, onImport: () -> Unit) {
    val home by vm.home.collectAsStateWithLifecycle()
    val txCount by vm.transactionCount.collectAsStateWithLifecycle()
    val firstDay by vm.firstDay.collectAsStateWithLifecycle()
    val lastDay by vm.lastDay.collectAsStateWithLifecycle()
    val recurring by vm.recurring.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        DataPill(txCount, firstDay, lastDay, recurring.size, onImport)
        Spacer(Modifier.height(36.dp))

        Text("AVAILABLE BALANCE", style = MaterialTheme.typography.labelLarge, color = Neon.Muted, letterSpacing = 3.sp)
        Spacer(Modifier.height(8.dp))
        BalanceField(
            text = home.balanceText,
            onChange = vm::onBalanceChange,
            onDone = { focus.clearFocus(); vm.go() },
        )
        AnimatedVisibility(home.inputError != null) {
            Text(home.inputError.orEmpty(), color = Neon.Pink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(32.dp))

        val broke = home.estimate?.let { it.highCents < 0 } == true
        GoButton(phase = home.phase, broke = broke, onClick = { focus.clearFocus(); vm.go() })
        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(
            visible = home.estimate != null,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) { it / 2 } + fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
        ) {
            home.estimate?.let { ResultCard(it, home.celebration) }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun DataPill(count: Int, first: Long?, last: Long?, recurring: Int, onImport: () -> Unit) {
    val text = if (count == 0) {
        "No data yet · tap to import a bank CSV 📥"
    } else {
        val range = if (first != null && last != null) {
            "${LocalDate.ofEpochDay(first).format(tinyDate)} – ${LocalDate.ofEpochDay(last).format(tinyDate)}"
        } else ""
        "📅 $count txns · $range · 🔁 $recurring recurring"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (count == 0) Neon.Night else Neon.Muted,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (count == 0) Neon.Lime else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onImport)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun BalanceField(text: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val t = rememberInfiniteTransition(label = "underline")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    BasicTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Display, fontSize = 46.sp, color = Color.White, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Neon.Lime),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onDone() }),
        visualTransformation = DollarPrefix,
        decorationBox = { inner ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                    if (text.isEmpty()) {
                        Text(
                            buildAnnotatedString {
                                withStyle(DollarStyle) { append("$ ") }
                                append("0.00")
                            },
                            style = TextStyle(fontFamily = Display, fontSize = 46.sp, color = Color.White.copy(alpha = 0.18f)),
                        )
                    }
                    inner()
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.8f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(Neon.Lime, Neon.Cyan, Neon.Pink), startX = -400f * shift, endX = 900f - 400f * shift)),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private val DollarStyle = SpanStyle(color = Neon.Lime, fontSize = 34.sp)

/** Renders a lime "$ " in front of the typed amount so the whole thing centers as one unit. */
private object DollarPrefix : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val out = buildAnnotatedString {
            withStyle(DollarStyle) { append("$ ") }
            append(text)
        }
        return TransformedText(out, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + 2
            override fun transformedToOriginal(offset: Int) = (offset - 2).coerceIn(0, text.length)
        })
    }
}

/**
 * Pill → spinning coin → pill. Squishes on press, pulses while idle, and wears brown when
 * the last result was negative.
 */
@Composable
private fun GoButton(phase: GoPhase, broke: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = 0.35f, stiffness = 600f), label = "press")

    val transition = updateTransition(phase, label = "go")
    val width by transition.animateDp(transitionSpec = { spring(dampingRatio = 0.5f, stiffness = 260f) }, label = "w") {
        if (it == GoPhase.Charging) 88.dp else 250.dp
    }
    val infinite = rememberInfiniteTransition(label = "go-inf")
    val pulse by infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing)), label = "pulse")
    val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "spin")

    val colors = when {
        phase == GoPhase.Charging -> listOf(Neon.Violet, Neon.Pink, Neon.Gold, Neon.Violet)
        phase == GoPhase.Done && broke -> listOf(Neon.Poop, Neon.PoopLight)
        phase == GoPhase.Done -> listOf(Neon.Pink, Neon.Gold)
        else -> listOf(Neon.Lime, Neon.Cyan)
    }
    val glow = colors.first()

    Box(
        Modifier
            .size(width = width, height = 88.dp)
            .scale(pressScale)
            .drawBehind {
                if (phase != GoPhase.Charging) {
                    val grow = 1f + 0.22f * pulse
                    val w = size.width * grow
                    val h = size.height * grow
                    drawRoundRect(
                        color = glow.copy(alpha = 0.45f * (1f - pulse)),
                        topLeft = androidx.compose.ui.geometry.Offset((size.width - w) / 2, (size.height - h) / 2),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = CornerRadius(h / 2, h / 2),
                    )
                }
            }
            .clip(RoundedCornerShape(50))
            .background(
                if (phase == GoPhase.Charging) Brush.sweepGradient(colors)
                else Brush.horizontalGradient(colors),
            )
            .border(2.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, enabled = phase != GoPhase.Charging) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { (scaleIn(initialScale = 0.3f) + fadeIn()) togetherWith (scaleOut(targetScale = 1.6f) + fadeOut()) },
            label = "go-label",
        ) { p ->
            when (p) {
                GoPhase.Idle -> Text("GO", style = TextStyle(fontFamily = Display, fontSize = 34.sp, color = Neon.Night))
                GoPhase.Charging -> Text("💸", fontSize = 34.sp, modifier = Modifier.rotate(spin))
                GoPhase.Done -> Text(
                    if (broke) "AGAIN 💩" else "AGAIN ↻",
                    style = TextStyle(fontFamily = Display, fontSize = 24.sp, color = if (broke) Color.White else Neon.Night),
                )
            }
        }
    }
}

@Composable
private fun ResultCard(e: Estimate, key: Int) {
    val broke = e.highCents < 0
    val accent = if (broke) Neon.PoopLight else Neon.Lime
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Neon.Card, Neon.Plum.copy(alpha = 0.9f))))
            .border(2.dp, Brush.linearGradient(if (broke) listOf(Neon.Poop, Neon.PoopLight) else listOf(Neon.Lime, Neon.Cyan, Neon.Pink)), RoundedCornerShape(28.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                broke -> "💩 THE FORECAST IS BROWN 💩"
                e.lowCents < 0 -> "😬 SPEND CAREFULLY"
                else -> "🤑 YOU CAN SPEND"
            },
            style = MaterialTheme.typography.headlineSmall, color = accent, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))

        if (e.lowCents == e.highCents) {
            CountUpMoney(e.highCents, key, 40.sp, if (broke) Neon.PoopLight else Color.White)
        } else {
            Text("WORST CASE", style = MaterialTheme.typography.labelMedium, color = Neon.Muted, letterSpacing = 2.sp)
            CountUpMoney(e.lowCents, key, 34.sp, if (e.lowCents < 0) Neon.PoopLight else Color.White)
            Text("to", style = MaterialTheme.typography.bodyMedium, color = Neon.Muted, modifier = Modifier.padding(vertical = 2.dp))
            Text("BEST CASE", style = MaterialTheme.typography.labelMedium, color = Neon.Muted, letterSpacing = 2.sp)
            CountUpMoney(e.highCents, key, 40.sp, if (broke) Neon.PoopLight else accent)
        }

        Spacer(Modifier.height(14.dp))
        val horizon = e.payday?.let {
            "Bills through ${e.horizonEnd.minusDays(1).format(shortDate)} · next payday ${it.date.format(shortDate)} (${it.source}, ${it.cadence})"
        } ?: "Bills through end of ${e.today.month.name.lowercase().replaceFirstChar(Char::titlecase)} · no regular payday detected"
        Text(horizon, style = MaterialTheme.typography.bodySmall, color = Neon.Muted, textAlign = TextAlign.Center)

        e.dataAgeDays?.takeIf { it > 10 }?.let { age ->
            Spacer(Modifier.height(10.dp))
            Text(
                "⚠️ Newest imported transaction is $age days old. Import a fresh CSV so bills that already posted aren't subtracted twice.",
                style = MaterialTheme.typography.bodySmall, color = Neon.Amber, textAlign = TextAlign.Center,
            )
        }
        if (e.dataAgeDays == null) {
            Spacer(Modifier.height(10.dp))
            Text("No transactions imported yet, so nothing was subtracted. Use ☰ → Import bank CSV.",
                style = MaterialTheme.typography.bodySmall, color = Neon.Amber, textAlign = TextAlign.Center)
        }

        if (e.items.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            val owed = e.items.filterNot { it.alreadyPaid }
            Summary("Starting balance", e.balanceCents, Color.White)
            Summary("High-confidence bills", -owed.filter { it.confidence == Confidence.HIGH }.sumOf { it.amountCents }, Neon.Lime)
            Summary("Medium + low confidence", -owed.filter { it.confidence != Confidence.HIGH }.sumOf { it.amountCents }, Neon.Amber)
            Spacer(Modifier.height(12.dp))
            e.items.forEach { DueRow(it) }
        } else if (e.dataAgeDays != null) {
            Spacer(Modifier.height(12.dp))
            Text("No recurring bills expected before then 🎉", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CountUpMoney(target: Long, key: Int, size: androidx.compose.ui.unit.TextUnit, color: Color) {
    val anim = remember(key, target) { Animatable(0f) }
    LaunchedEffect(key, target) { anim.animateTo(target.toFloat(), tween(1700, easing = FastOutSlowInEasing)) }
    Text(
        formatCents(anim.value.toLong()),
        style = TextStyle(fontFamily = Display, fontSize = size, color = color, textAlign = TextAlign.Center),
    )
}

@Composable
private fun Summary(label: String, cents: Long, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Neon.Muted, modifier = Modifier.weight(1f))
        Text(formatCents(cents), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun DueRow(item: DueItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.dueDate.format(tinyDate), style = MaterialTheme.typography.labelMedium, color = Neon.Muted, modifier = Modifier.width(52.dp))
        Text(
            item.name,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (item.alreadyPaid) TextDecoration.LineThrough else null,
            color = if (item.alreadyPaid) Neon.Muted else Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ConfidenceChip(item.confidence)
        Spacer(Modifier.width(8.dp))
        Text(
            if (item.alreadyPaid) "paid ✓" else formatCents(item.amountCents),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = if (item.alreadyPaid) Neon.Muted else Color.White,
        )
    }
}

@Composable
fun ConfidenceChip(c: Confidence) {
    val (label, color) = when (c) {
        Confidence.HIGH -> "HIGH" to Neon.Lime
        Confidence.MEDIUM -> "MED" to Neon.Amber
        Confidence.LOW -> "LOW" to Neon.Muted
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
