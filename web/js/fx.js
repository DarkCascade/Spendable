// Fireworks + emoji party on a full-screen canvas. A port of the Android app's ui/fx/Celebration.kt.
// mood "rich": neon fireworks, hearts/stars/smileys/dollar signs, money rain.
// mood "broke": brown fireworks, poop- and frown-shaped bursts, and way more 💩 than 💰.

const RICH_PALETTE = ["#C6FF3D", "#5FE6FF", "#FF5FD2", "#FFC94A", "#9B6BFF", "#FFFFFF"];
const BROKE_PALETTE = ["#5C3A1E", "#7B4B2A", "#8B5A2B", "#A0522D", "#6F4E37", "#B07A4F"];

const rep = (n, x) => Array(n).fill(x);
const sym = (mag) => (Math.random() * 2 - 1) * mag;
const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];

export class Celebration {
  /** @param {HTMLCanvasElement} canvas */
  constructor(canvas, { onBurst } = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.onBurst = onBurst ?? (() => {});
    this.raf = 0;
    new ResizeObserver(() => this.resize()).observe(canvas);
    this.resize();
  }

  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const { clientWidth: w, clientHeight: h } = this.canvas;
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.w = w; this.h = h;
  }

  /** Starts a fresh show (replacing any running one). */
  play(mood) {
    cancelAnimationFrame(this.raf);
    const rich = mood === "rich";
    this.rich = rich;
    this.d = 1; // CSS px are already density-independent
    this.gravity = 520;
    this.t = 0;
    this.flash = 0;
    this.flashColor = "#fff";
    this.sparks = [];
    this.rockets = [];
    this.emojis = [];
    this.palette = rich ? RICH_PALETTE : BROKE_PALETTE;
    this.burstCycle = rich
      ? ["RING", "HEART", "DOLLAR", "EMOJI", "STAR", "SMILEY", "RING", "DOLLAR", "EMOJI", "HEART", "STAR"]
      : ["RING", "POOP", "EMOJI", "FROWN", "POOP", "RING", "EMOJI", "POOP", "FROWN", "EMOJI", "POOP"];
    this.rainGlyphs = rich
      ? [...rep(4, "💰"), ...rep(3, "💵"), ...rep(3, "🪙"), "🤑", "🤑", "💸", "💎", "✨", "🎉"]
      : [...rep(18, "💩"), "💰", "💸"];
    this.burstGlyphs = rich ? ["💰", "🪙", "💵", "🤑", "⭐"] : [...rep(9, "💩"), "💸"];
    this.launches = this.burstCycle.map((_, i) => 0.15 + i * 0.36 + Math.random() * 0.2);
    this.burstIndex = 0;
    this.rainTotal = rich ? 60 : 90;
    this.rainSpawned = 0;
    this.rainDuration = 4.2;
    let last = performance.now();
    const frame = (now) => {
      const dt = Math.min((now - last) / 1000, 1 / 20);
      last = now;
      this.step(dt);
      this.draw();
      if (this.alive()) this.raf = requestAnimationFrame(frame);
      else this.ctx.clearRect(0, 0, this.w, this.h);
    };
    this.raf = requestAnimationFrame(frame);
  }

  alive() {
    return this.launches.length || this.rainSpawned < this.rainTotal || this.rockets.length || this.sparks.length || this.emojis.length;
  }

  step(dt) {
    if (!this.w) return;
    this.t += dt;
    this.flash = Math.max(0, this.flash - dt * 2.5);
    while (this.launches.length && this.launches[0] <= this.t) { this.launches.shift(); this.launchRocket(); }
    const rainDue = Math.min(this.rainTotal, Math.floor(this.rainTotal * (this.t / this.rainDuration)));
    while (this.rainSpawned < rainDue) { this.spawnRain(); this.rainSpawned++; }

    for (let i = this.rockets.length - 1; i >= 0; i--) {
      const r = this.rockets[i];
      r.vy += this.gravity * dt;
      r.x += r.vx * dt; r.y += r.vy * dt;
      this.sparks.push(this.spark(r.x, r.y, sym(30), Math.random() * 60, r.color, 1.6, 3, 0.3, 0.35 + Math.random() * 0.25));
      if (r.vy > -40) { this.rockets.splice(i, 1); this.explode(r); }
    }
    for (let i = this.sparks.length - 1; i >= 0; i--) {
      const s = this.sparks[i];
      s.life -= dt;
      if (s.life <= 0) { this.sparks.splice(i, 1); continue; }
      const k = Math.exp(-s.drag * dt);
      s.vx *= k; s.vy *= k;
      s.vy += this.gravity * s.gScale * dt;
      s.px = s.x; s.py = s.y;
      s.x += s.vx * dt; s.y += s.vy * dt;
    }
    for (let i = this.emojis.length - 1; i >= 0; i--) {
      const e = this.emojis[i];
      e.life -= dt;
      if (e.life <= 0 || e.y > this.h + e.size * 2) { this.emojis.splice(i, 1); continue; }
      const k = Math.exp(-e.drag * dt);
      e.vx *= k; e.vy *= k;
      e.vy += this.gravity * e.gScale * dt;
      e.x += (e.vx + e.sway * Math.sin(this.t * 3 + e.swayPhase)) * dt;
      e.y += e.vy * dt;
      e.rot += e.spin * dt;
    }
  }

  spark(x, y, vx, vy, color, radius, drag, gScale, life) {
    return { x, y, px: x, py: y, vx, vy, color, radius, drag, gScale, maxLife: life, life };
  }

  emoji(x, y, vx, vy, glyph, size, drag, gScale, life, extra = {}) {
    return { x, y, vx, vy, rot: sym(30), spin: sym(360), glyph, size, drag, gScale, maxLife: life, life, swayPhase: 0, sway: 0, ...extra };
  }

  launchRocket() {
    const x = this.w * (0.15 + Math.random() * 0.7);
    const y = this.h + 10;
    const apex = this.h * (0.12 + Math.random() * 0.33);
    const vy = -Math.sqrt(2 * this.gravity * (y - apex));
    const burst = this.burstCycle[this.burstIndex++ % this.burstCycle.length];
    this.rockets.push({ x, y, vx: sym(40), vy, color: pick(this.palette), burst });
  }

  explode(r) {
    this.onBurst();
    this.flash = 1;
    this.flashColor = r.color;
    if (r.burst === "RING") {
      for (let i = 0; i < 90; i++) {
        const a = Math.random() * Math.PI * 2;
        const sp = 100 + Math.random() * 300;
        this.sparks.push(this.spark(r.x, r.y, Math.cos(a) * sp, Math.sin(a) * sp,
          Math.random() < 0.25 ? pick(this.palette) : r.color, 2.2, 1.8, 0.35, 1.3 + Math.random() * 0.8));
      }
    } else if (r.burst === "EMOJI") {
      for (let i = 0; i < 16; i++) {
        const a = Math.random() * Math.PI * 2;
        const sp = 160 + Math.random() * 320;
        this.emojis.push(this.emoji(r.x, r.y, Math.cos(a) * sp, Math.sin(a) * sp, pick(this.burstGlyphs), 26 + Math.random() * 18, 1.4, 0.55, 2.4 + Math.random()));
      }
      this.shapeBurst(r, ringShape(40), 70);
    } else {
      this.shapeBurst(r, SHAPES[r.burst](), 150);
    }
  }

  /**
   * Sparks launched with velocity proportional to their target point. Drag slows them all by
   * the same factor, so they coast into the outline (displacement → v0 / drag) and hold it.
   */
  shapeBurst(r, points, scale) {
    const drag = 2.2;
    const sp = scale * drag;
    for (const [px, py] of points) {
      this.sparks.push(this.spark(r.x, r.y, px * sp + sym(6), py * sp + sym(6), r.color, 2.6, drag, 0.12, 1.9 + Math.random() * 0.5));
    }
    for (let i = 0; i < 24; i++) {
      const a = Math.random() * Math.PI * 2;
      const s = 60 + Math.random() * 160;
      this.sparks.push(this.spark(r.x, r.y, Math.cos(a) * s, Math.sin(a) * s, pick(this.palette), 1.4, 2.5, 0.4, 0.8 + Math.random() * 0.6));
    }
  }

  spawnRain() {
    const size = 24 + Math.random() * 22;
    this.emojis.push(this.emoji(Math.random() * this.w, -size - Math.random() * 120, sym(20), 90 + Math.random() * 160,
      pick(this.rainGlyphs), size, 0.4, 0.18, 9, { swayPhase: Math.random() * 6, sway: 30, spin: sym(120) }));
  }

  draw() {
    const { ctx } = this;
    ctx.clearRect(0, 0, this.w, this.h);
    if (this.flash > 0) {
      ctx.globalAlpha = this.flash * (this.rich ? 0.10 : 0.16);
      ctx.fillStyle = this.flashColor;
      ctx.fillRect(0, 0, this.w, this.h);
    }
    for (const r of this.rockets) {
      ctx.globalAlpha = 0.5; ctx.fillStyle = r.color;
      ctx.beginPath(); ctx.arc(r.x, r.y, 7, 0, Math.PI * 2); ctx.fill();
      ctx.globalAlpha = 1; ctx.fillStyle = "#fff";
      ctx.beginPath(); ctx.arc(r.x, r.y, 3.2, 0, Math.PI * 2); ctx.fill();
    }
    ctx.lineCap = "round";
    for (const s of this.sparks) {
      const a = Math.max(0, Math.min(1, s.life / s.maxLife));
      const twinkle = a < 0.35 && Math.floor(s.x + this.t * 40) % 3 === 0 ? 0.3 : 1;
      ctx.strokeStyle = ctx.fillStyle = s.color;
      ctx.globalAlpha = a * 0.55 * twinkle;
      ctx.lineWidth = s.radius * a;
      ctx.beginPath(); ctx.moveTo(s.px, s.py); ctx.lineTo(s.x, s.y); ctx.stroke();
      ctx.globalAlpha = a * twinkle;
      ctx.beginPath(); ctx.arc(s.x, s.y, s.radius * (0.45 + 0.55 * a), 0, Math.PI * 2); ctx.fill();
    }
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    for (const e of this.emojis) {
      ctx.globalAlpha = Math.max(0, Math.min(1, e.life / 0.6));
      ctx.font = `${e.size}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`;
      ctx.save();
      ctx.translate(e.x, e.y);
      ctx.rotate((e.rot * Math.PI) / 180);
      ctx.fillText(e.glyph, 0, 0);
      ctx.restore();
    }
    ctx.globalAlpha = 1;
  }
}

// ---- Shape outlines in unit space (y down), roughly within [-1, 1] ----

function ringShape(n) {
  return Array.from({ length: n }, (_, i) => { const a = (i * 2 * Math.PI) / n; return [Math.cos(a), Math.sin(a)]; });
}

function heart() {
  return Array.from({ length: 90 }, (_, i) => {
    const a = (i * 2 * Math.PI) / 90;
    const x = 16 * Math.sin(a) ** 3;
    const y = -(13 * Math.cos(a) - 5 * Math.cos(2 * a) - 2 * Math.cos(3 * a) - Math.cos(4 * a));
    return [x / 17, y / 17];
  });
}

function star() {
  const corners = Array.from({ length: 10 }, (_, i) => {
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    const rad = i % 2 === 0 ? 1 : 0.42;
    return [Math.cos(a) * rad, Math.sin(a) * rad];
  });
  return corners.flatMap(([x0, y0], i) => {
    const [x1, y1] = corners[(i + 1) % corners.length];
    return Array.from({ length: 9 }, (_, k) => [x0 + ((x1 - x0) * k) / 9, y0 + ((y1 - y0) * k) / 9]);
  });
}

function face(smile) {
  const eyes = [-0.35, 0.35].flatMap((ex) => Array.from({ length: 6 }, (_, i) => {
    const a = (i * Math.PI) / 3;
    return [ex + Math.cos(a) * 0.09, -0.28 + Math.sin(a) * 0.09];
  }));
  const mouth = Array.from({ length: 18 }, (_, i) => {
    const a = (0.15 + (0.7 * i) / 17) * Math.PI;
    return smile ? [Math.cos(a) * 0.55, 0.05 + Math.sin(a) * 0.45] : [Math.cos(a) * 0.5, 0.75 - Math.sin(a) * 0.35];
  });
  return [...ringShape(56), ...eyes, ...mouth];
}

function dollar() {
  const r = 0.36;
  const rad = (deg) => (deg * Math.PI) / 180;
  const upper = Array.from({ length: 26 }, (_, i) => { const a = rad(-30 - (240 * i) / 25); return [Math.cos(a) * r, -r + Math.sin(a) * r]; });
  const lower = Array.from({ length: 26 }, (_, i) => { const a = rad(-90 + (300 * i) / 25); return [Math.cos(a) * r, r + Math.sin(a) * r]; });
  const bar = Array.from({ length: 12 }, (_, i) => [0, -1 + (2 * i) / 11]);
  return [...upper, ...lower, ...bar];
}

function poop() {
  const tier = (cy, w, h, n) => Array.from({ length: n }, (_, i) => {
    const a = Math.PI + (Math.PI * i) / (n - 1);
    return [Math.cos(a) * w, cy + Math.sin(a) * h];
  });
  const base = Array.from({ length: 12 }, (_, i) => [-1 + (2 * i) / 11, 0.62]);
  const tip = Array.from({ length: 10 }, (_, i) => {
    const a = ((200 + (200 * i) / 9) * Math.PI) / 180;
    return [0.08 + Math.cos(a) * 0.12, -0.62 + Math.sin(a) * 0.12];
  });
  return [...tier(0.62, 1, 0.42, 26), ...tier(0.18, 0.72, 0.36, 20), ...tier(-0.2, 0.45, 0.32, 16), ...base, ...tip];
}

const SHAPES = { HEART: heart, STAR: star, SMILEY: () => face(true), FROWN: () => face(false), DOLLAR: dollar, POOP: poop };
