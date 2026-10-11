package com.darkcascade.spendable.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darkcascade.spendable.ui.fx.CelebrationOverlay
import com.darkcascade.spendable.ui.fx.Mood
import com.darkcascade.spendable.ui.theme.Display
import com.darkcascade.spendable.ui.theme.Neon
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

enum class Screen(val title: String, val emoji: String) {
    Home("Calculator", "🧮"),
    Recurring("Recurring", "🔁"),
    Transactions("Transactions", "🧾"),
    Jev("Jev classifier", "🧠"),
}

private val CSV_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "application/csv", "text/plain",
    "application/vnd.ms-excel", "application/octet-stream",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendableApp(vm: MainViewModel = viewModel()) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var confirmReset by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val home by vm.home.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val txCount by vm.transactionCount.collectAsStateWithLifecycle()
    val recurring by vm.recurring.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
    }
    val openImport = { scope.launch { drawerState.close() }; picker.launch(CSV_TYPES) }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        // Consume only after it's shown: clearing first would change the key and cancel this effect.
        snackbar.showSnackbar(m, withDismissAction = true, duration = SnackbarDuration.Long)
        vm.consumeMessage()
    }
    BackHandler(enabled = screen != Screen.Home && !drawerState.isOpen) { screen = Screen.Home }

    val broke = home.estimate?.let { it.highCents < 0 } == true

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Neon.Plum) {
                Spacer(Modifier.height(24.dp))
                Text("SPENDABLE", style = MaterialTheme.typography.headlineMedium, color = Neon.Lime, modifier = Modifier.padding(horizontal = 28.dp))
                Text(
                    "$txCount transactions · ${recurring.size} recurring",
                    style = MaterialTheme.typography.bodySmall, color = Neon.Muted,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                val itemColors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = Neon.Lime.copy(alpha = 0.16f),
                    unselectedContainerColor = Color.Transparent,
                )
                NavigationDrawerItem(
                    label = { Text("📥  Import bank CSV") },
                    selected = false, onClick = { openImport() }, colors = itemColors,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
                Screen.entries.forEach { s ->
                    NavigationDrawerItem(
                        label = { Text("${s.emoji}  ${s.title}") },
                        selected = screen == s,
                        onClick = { screen = s; scope.launch { drawerState.close() } },
                        colors = itemColors,
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
                NavigationDrawerItem(
                    label = { Text("🗑️  Reset all data") },
                    selected = false, onClick = { confirmReset = true; scope.launch { drawerState.close() } }, colors = itemColors,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedBackground(broke = broke && screen == Screen.Home)
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            if (screen == Screen.Home) ShimmerTitle("SPENDABLE")
                            else Text(screen.title, style = MaterialTheme.typography.headlineSmall)
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                    },
                    modifier = Modifier.padding(padding),
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(vm, onImport = { openImport() })
                        Screen.Recurring -> RecurringScreen(vm)
                        Screen.Transactions -> TransactionsScreen(vm)
                        Screen.Jev -> JevScreen(vm)
                    }
                }
            }
            if (screen == Screen.Home) {
                CelebrationOverlay(trigger = home.celebration, mood = if (broke) Mood.BROKE else Mood.RICH)
            }
            BusyOverlay(busy)
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset everything?") },
            text = { Text("Deletes every imported transaction and all recurring analysis from this phone. Your Jev settings are kept.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.clearAll() }) { Text("Delete", color = Neon.Pink) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep") } },
            containerColor = Neon.Card,
        )
    }
}

@Composable
private fun ShimmerTitle(text: String) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "x")
    val brush = Brush.linearGradient(
        colors = listOf(Neon.Lime, Neon.Cyan, Neon.Pink, Neon.Gold, Neon.Lime),
        start = Offset(-600f + 1200f * x, 0f),
        end = Offset(0f + 1200f * x, 80f),
    )
    Text(text, style = TextStyle(fontFamily = Display, fontSize = 26.sp, brush = brush))
}

/** Slow-drifting neon blobs. Turns muddy brown when the forecast is negative. */
@Composable
private fun AnimatedBackground(broke: Boolean) {
    val t = rememberInfiniteTransition(label = "bg")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "phase")
    val c1 by animateColorAsState(if (broke) Color(0xFF6F4E37) else Neon.Violet, tween(1200), label = "c1")
    val c2 by animateColorAsState(if (broke) Color(0xFF8B5A2B) else Neon.Pink, tween(1200), label = "c2")
    val c3 by animateColorAsState(if (broke) Color(0xFF4B2E19) else Neon.Cyan, tween(1200), label = "c3")
    Canvas(Modifier.fillMaxSize().background(Neon.Night)) {
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float, a: Float) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        blob(c1, w * (0.25f + 0.15f * cos(phase)), h * (0.22f + 0.08f * sin(phase * 2)), w * 0.9f, 0.38f)
        blob(c2, w * (0.85f + 0.12f * sin(phase)), h * (0.62f + 0.1f * cos(phase)), w * 0.8f, 0.22f)
        blob(c3, w * (0.15f + 0.1f * sin(phase * 1.5f)), h * (0.92f + 0.05f * cos(phase)), w * 0.7f, 0.18f)
    }
}

@Composable
private fun BusyOverlay(label: String?) {
    AnimatedVisibility(visible = label != null, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(color = Neon.Lime, strokeWidth = 5.dp, modifier = Modifier.size(56.dp))
                Text(label.orEmpty(), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
