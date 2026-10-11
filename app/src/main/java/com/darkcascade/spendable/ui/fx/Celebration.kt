package com.darkcascade.spendable.ui.fx

import android.graphics.Paint
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Mood { RICH, BROKE }

/**
 * Full-screen fireworks + emoji party. Each new [trigger] value starts a fresh show.
 * Draws only; never consumes touches.
 */
@Composable
fun CelebrationOverlay(trigger: Int, mood: Mood, modifier: Modifier = Modifier) {
    if (trigger == 0) return
    val density = LocalDensity.current.density
    val view = LocalView.current
    val sim = remember(trigger) {
        FxSim(mood, density, Random(trigger * 7919L + System.nanoTime())) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }
    var frame by remember(trigger) { mutableLongStateOf(0L) }
    LaunchedEffect(sim) {
        var last = -1L
        while (sim.alive) {
            withFrameNanos { now ->
                if (last >= 0) sim.step(((now - last) / 1e9f).coerceAtMost(1 / 20f))
                last = now
                frame = now
            }
        }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") frame // subscribe to frame ticks
        sim.width = size.width
        sim.height = size.height
        sim.draw(this, paint)
    }
}

private enum class Burst { RING, HEART, STAR, SMILEY, DOLLAR, EMOJI, POOP, FROWN }

private class Spark(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    val color: Color, val radius: Float, val drag: Float, val gravityScale: Float,
    val maxLife: Float, var life: Float = maxLife, var px: Float = x, var py: Float = y,
)

private class Rocket(var x: Float, var y: Float, val vx: Float, var vy: Float, val color: Color, val burst: Burst)

private class EmojiParticle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var rot: Float, val spin: Float, val glyph: String, val size: Float,
    val drag: Float, val gravityScale: Float, val maxLife: Float, var life: Float = maxLife,
    val swayPhase: Float = 0f, val sway: Float = 0f,
)

private class FxSim(val mood: Mood, val d: Float, val rnd: Random, val onBurst: () -> Unit) {
    var width = 0f
    var height = 0f

    private val gravity = 520f * d
    private var t = 0f
    private var flash = 0f
    private var flashColor = Color.White

    private val sparks = ArrayList<Spark>(1200)
    private val rockets = ArrayList<Rocket>()
    private val emojis = ArrayList<EmojiParticle>()

    private val rich = mood == Mood.RICH
    private val palette = if (rich) {
        listOf(Color(0xFFC6FF3D), Color(0xFF5FE6FF), Color(0xFFFF5FD2), Color(0xFFFFC94A), Color(0xFF9B6BFF), Color.White)
    } else {
        listOf(Color(0xFF5C3A1E), Color(0xFF7B4B2A), Color(0xFF8B5A2B), Color(0xFFA0522D), Color(0xFF6F4E37), Color(0xFFB07A4F))
    }
    private val burstCycle = if (rich) {
        listOf(Burst.RING, Burst.HEART, Burst.DOLLAR, Burst.EMOJI, Burst.STAR, Burst.SMILEY, Burst.RING, Burst.DOLLAR, Burst.EMOJI, Burst.HEART, Burst.STAR)
    } else {
        listOf(Burst.RING, Burst.POOP, Burst.EMOJI, Burst.FROWN, Burst.POOP, Burst.RING, Burst.EMOJI, Burst.POOP, Burst.FROWN, Burst.EMOJI, Burst.POOP)
    }

    // Weighted emoji bags. Broke mode: way more 💩 than 💰.
    private val rainGlyphs = if (rich) {
        List(4) { "💰" } + List(3) { "💵" } + List(3) { "🪙" } + listOf("🤑", "🤑", "💸", "💎", "✨", "🎉")
    } else {
        List(18) { "💩" } + listOf("💰", "💸")
    }
    private val burstGlyphs = if (rich) listOf("💰", "🪙", "💵", "🤑", "⭐") else List(9) { "💩" } + listOf("💸")

    private val launches = ArrayDeque(List(burstCycle.size) { i -> 0.15f + i * 0.36f + rnd.nextFloat() * 0.2f })
    private var burstIndex = 0
    private val rainTotal = if (rich) 60 else 90
    private var rainSpawned = 0
    private val rainDuration = 4.2f

    val alive: Boolean
        get() = launches.isNotEmpty() || rainSpawned < rainTotal || rockets.isNotEmpty() || sparks.isNotEmpty() || emojis.isNotEmpty()

    fun step(dt: Float) {
        if (width <= 0f) return
        t += dt
        flash = (flash - dt * 2.5f).coerceAtLeast(0f)

        while (launches.isNotEmpty() && launches.first() <= t) {
            launches.removeFirst()
            launchRocket()
        }
        val rainDue = (rainTotal * (t / rainDuration)).toInt().coerceAtMost(rainTotal)
        while (rainSpawned < rainDue) { spawnRain(); rainSpawned++ }

        val rIt = rockets.iterator()
        while (rIt.hasNext()) {
            val r = rIt.next()
            r.vy += gravity * dt
            r.x += r.vx * dt
            r.y += r.vy * dt
            // Sputtering trail.
            sparks += Spark(r.x, r.y, rnd.sym(30f * d), rnd.nextFloat() * 60f * d, r.color, 1.6f * d, 3f, 0.3f, 0.35f + rnd.nextFloat() * 0.25f)
            if (r.vy > -40f * d) {
                rIt.remove()
                explode(r)
            }
        }

        val sIt = sparks.iterator()
        while (sIt.hasNext()) {
            val s = sIt.next()
            s.life -= dt
            if (s.life <= 0f) { sIt.remove(); continue }
            val k = exp(-s.drag * dt)
            s.vx *= k; s.vy *= k
            s.vy += gravity * s.gravityScale * dt
            s.px = s.x; s.py = s.y
            s.x += s.vx * dt; s.y += s.vy * dt
        }

        val eIt = emojis.iterator()
        while (eIt.hasNext()) {
            val e = eIt.next()
            e.life -= dt
            if (e.life <= 0f || e.y > height + e.size * 2) { eIt.remove(); continue }
            val k = exp(-e.drag * dt)
            e.vx *= k; e.vy *= k
            e.vy += gravity * e.gravityScale * dt
            e.x += (e.vx + e.sway * sin(t * 3f + e.swayPhase)) * dt
            e.y += e.vy * dt
            e.rot += e.spin * dt
        }
    }

    private fun launchRocket() {
        val x = width * (0.15f + rnd.nextFloat() * 0.7f)
        val y = height + 10f * d
        val apex = height * (0.12f + rnd.nextFloat() * 0.33f)
        val vy = -sqrt(2f * gravity * (y - apex))
        val burst = burstCycle[burstIndex++ % burstCycle.size]
        rockets += Rocket(x, y, rnd.sym(40f * d), vy, palette.random(rnd), burst)
    }

    private fun explode(r: Rocket) {
        onBurst()
        flash = 1f
        flashColor = r.color
        val color = r.color
        when (r.burst) {
            Burst.RING -> repeat(90) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val sp = (100f + rnd.nextFloat() * 300f) * d
                sparks += Spark(r.x, r.y, cos(a) * sp, sin(a) * sp, if (rnd.nextFloat() < 0.25f) palette.random(rnd) else color,
                    2.2f * d, 1.8f, 0.35f, 1.3f + rnd.nextFloat() * 0.8f)
            }
            Burst.EMOJI -> repeat(16) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val sp = (160f + rnd.nextFloat() * 320f) * d
                emojis += EmojiParticle(r.x, r.y, cos(a) * sp, sin(a) * sp, rnd.sym(40f), rnd.sym(360f), burstGlyphs.random(rnd),
                    (26f + rnd.nextFloat() * 18f) * d, 1.4f, 0.55f, 2.4f + rnd.nextFloat())
            }.also { shapeBurst(r, ringShape(40), color, scale = 70f) }
            else -> shapeBurst(r, shapeFor(r.burst), color, scale = 150f)
        }
    }

    /**
     * Sparks launched with velocity proportional to their target point. Drag slows them all
     * by the same factor, so they coast into the outline (displacement → v0 / drag) and hold it.
     */
    private fun shapeBurst(r: Rocket, points: List<Pair<Float, Float>>, color: Color, scale: Float) {
        val drag = 2.2f
        val sp = scale * d * drag
        val gScale = 0.12f
        for ((px, py) in points) {
            sparks += Spark(r.x, r.y, px * sp + rnd.sym(6f * d), py * sp + rnd.sym(6f * d), color, 2.6f * d, drag, gScale, 1.9f + rnd.nextFloat() * 0.5f)
        }
        // A little glitter around the outline.
        repeat(24) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = (60f + rnd.nextFloat() * 160f) * d
            sparks += Spark(r.x, r.y, cos(a) * s, sin(a) * s, palette.random(rnd), 1.4f * d, 2.5f, 0.4f, 0.8f + rnd.nextFloat() * 0.6f)
        }
    }

    private fun spawnRain() {
        val size = (24f + rnd.nextFloat() * 22f) * d
        emojis += EmojiParticle(
            x = rnd.nextFloat() * width, y = -size - rnd.nextFloat() * 120f * d,
            vx = rnd.sym(20f * d), vy = (90f + rnd.nextFloat() * 160f) * d,
            rot = rnd.sym(30f), spin = rnd.sym(120f), glyph = rainGlyphs.random(rnd), size = size,
            drag = 0.4f, gravityScale = 0.18f, maxLife = 9f, swayPhase = rnd.nextFloat() * 6f, sway = 30f * d,
        )
    }

    fun draw(scope: DrawScope, paint: Paint) = with(scope) {
        if (flash > 0f) drawRect(flashColor.copy(alpha = flash * if (rich) 0.10f else 0.16f))
        for (r in rockets) {
            drawCircle(Color.White, 3.2f * d, Offset(r.x, r.y))
            drawCircle(r.color.copy(alpha = 0.5f), 7f * d, Offset(r.x, r.y))
        }
        for (s in sparks) {
            val a = (s.life / s.maxLife).coerceIn(0f, 1f)
            val twinkle = if (a < 0.35f && ((s.x + t * 40f).toInt() % 3 == 0)) 0.3f else 1f
            drawLine(s.color.copy(alpha = a * 0.55f * twinkle), Offset(s.px, s.py), Offset(s.x, s.y), s.radius * a, StrokeCap.Round)
            drawCircle(s.color.copy(alpha = a * twinkle), s.radius * (0.45f + 0.55f * a), Offset(s.x, s.y))
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            for (e in emojis) {
                val fade = (e.life / 0.6f).coerceIn(0f, 1f)
                paint.textSize = e.size
                paint.alpha = (255 * fade).toInt()
                nc.save()
                nc.translate(e.x, e.y)
                nc.rotate(e.rot)
                nc.drawText(e.glyph, 0f, e.size * 0.35f, paint)
                nc.restore()
            }
        }
    }

    // ---- Shape outlines in unit space (y down), roughly within [-1, 1] ----

    private fun shapeFor(b: Burst): List<Pair<Float, Float>> = when (b) {
        Burst.HEART -> heart()
        Burst.STAR -> star()
        Burst.SMILEY -> face(smile = true)
        Burst.FROWN -> face(smile = false)
        Burst.DOLLAR -> dollar()
        Burst.POOP -> poop()
        else -> ringShape(80)
    }

    private fun ringShape(n: Int) = List(n) { i ->
        val a = i * 2f * PI.toFloat() / n
        cos(a) to sin(a)
    }

    private fun heart() = List(90) { i ->
        val a = i * 2f * PI.toFloat() / 90
        val x = 16f * sin(a).pow(3)
        val y = -(13f * cos(a) - 5f * cos(2 * a) - 2f * cos(3 * a) - cos(4 * a))
        x / 17f to y / 17f
    }

    private fun star(): List<Pair<Float, Float>> {
        val corners = List(10) { i ->
            val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
            val rad = if (i % 2 == 0) 1f else 0.42f
            cos(a) * rad to sin(a) * rad
        }
        return corners.indices.flatMap { i ->
            val (x0, y0) = corners[i]
            val (x1, y1) = corners[(i + 1) % corners.size]
            List(9) { k -> val f = k / 9f; x0 + (x1 - x0) * f to y0 + (y1 - y0) * f }
        }
    }

    private fun face(smile: Boolean): List<Pair<Float, Float>> {
        val outline = ringShape(56)
        val eyes = listOf(-0.35f, 0.35f).flatMap { ex -> List(6) { i -> val a = i * PI.toFloat() / 3; ex + cos(a) * 0.09f to -0.28f + sin(a) * 0.09f } }
        val mouth = List(18) { i ->
            val a = (0.15f + 0.7f * i / 17f) * PI.toFloat()
            if (smile) cos(a) * 0.55f to 0.05f + sin(a) * 0.45f
            else cos(a) * 0.5f to 0.75f - sin(a) * 0.35f
        }
        return outline + eyes + mouth
    }

    private fun dollar(): List<Pair<Float, Float>> {
        val r = 0.36f
        val upper = List(26) { i -> val a = (-30f - 240f * i / 25f) * PI.toFloat() / 180f; cos(a) * r to -r + sin(a) * r }
        val lower = List(26) { i -> val a = (-90f + 300f * i / 25f) * PI.toFloat() / 180f; cos(a) * r to r + sin(a) * r }
        val bar = List(12) { i -> 0f to -1f + 2f * i / 11f }
        return upper + lower + bar
    }

    private fun poop(): List<Pair<Float, Float>> {
        // Three stacked tiers (upper arcs + base line) and a curly tip.
        fun tier(cy: Float, w: Float, h: Float, n: Int) = List(n) { i ->
            val a = PI.toFloat() + PI.toFloat() * i / (n - 1)
            cos(a) * w to cy + sin(a) * h
        }
        val base = List(12) { i -> -1f + 2f * i / 11f to 0.62f }
        val tip = List(10) { i -> val a = (200f + 200f * i / 9f) * PI.toFloat() / 180f; 0.08f + cos(a) * 0.12f to -0.62f + sin(a) * 0.12f }
        return tier(0.62f, 1f, 0.42f, 26) + tier(0.18f, 0.72f, 0.36f, 20) + tier(-0.2f, 0.45f, 0.32f, 16) + base + tip
    }

    private fun Random.sym(mag: Float) = (nextFloat() * 2f - 1f) * mag
}
