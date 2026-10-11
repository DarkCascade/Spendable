// Spendable web app: UI wiring. Logic lives in csv.js / engine.js / db.js; effects in fx.js.

import { parseCsv, merchantKey, assignKeys, parseCents } from "./csv.js";
import { analyze, nextPayday, estimate } from "./engine.js";
import { allTransactions, importRows, allRecurring, replaceRecurring, clearAll, requestPersistence } from "./db.js";
import { Celebration } from "./fx.js";
import { todayEpochDay, fmtShort, fmtWeekday, fmtLong, fmtMonth } from "./dates.js";

const $ = (sel) => document.querySelector(sel);
const money = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
const fmt = (cents) => money.format(cents / 100);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
};

let transactions = [];
let recurring = [];
let flowFilter = "EXPENSE";
let goBusy = false;

const toTxn = (r) => ({ day: r.day, merchantKey: r.merchantKey, displayName: r.description, rawDescription: r.originalDescription, category: r.category, amountCents: r.amountCents });

// ---------------------------------------------------------------- haptics

const hapticLabel = $("#hapticLabel");
function haptic(pattern = 12) {
  if (navigator.vibrate) navigator.vibrate(pattern);   // Android Chrome
  else hapticLabel.click();                             // iOS 18+: toggling a native switch ticks the Taptic Engine
}

const fx = new Celebration($("#fx"), { onBurst: () => haptic(8) });

// ---------------------------------------------------------------- data

async function refresh() {
  [transactions, recurring] = await Promise.all([allTransactions(), allRecurring()]);
  renderPill();
  renderRecurring();
  renderTransactions();
  $("#drawerStats").textContent = `${transactions.length} transactions · ${recurring.length} recurring`;
}

async function reanalyze() {
  const posted = (await allTransactions()).filter((r) => !r.pending).map(toTxn);
  const candidates = analyze(posted);
  await replaceRecurring(candidates);
  await refresh();
  const exp = candidates.filter((c) => c.flow === "EXPENSE");
  const n = (lvl) => exp.filter((c) => c.confidence === lvl).length;
  return `Recurring: ${n("HIGH")} high · ${n("MEDIUM")} med · ${n("LOW")} low · ${candidates.length - exp.length} income`;
}

async function importFile(file) {
  await busy("Ingesting transactions…", async () => {
    const parsed = parseCsv(await file.text());
    const keyed = await assignKeys(parsed.transactions);
    const now = Date.now();
    const rows = keyed.map(({ id, row }) => ({
      id,
      day: row.day,
      description: row.description,
      originalDescription: row.originalDescription,
      merchantKey: merchantKey(row.description, parsed.hasCleanMerchantNames),
      category: row.category,
      amountCents: row.amountCents,
      pending: row.pending,
      importedAt: now,
    }));
    const added = await importRows(rows);
    const dupes = rows.length - added;
    const summary = await reanalyze();
    requestPersistence();
    resetResult();
    let msg = `${parsed.formatName}: ${added} new, ${dupes} duplicate${dupes === 1 ? "" : "s"} skipped`;
    if (parsed.skippedRows) msg += `, ${parsed.skippedRows} unreadable rows`;
    toast(`${msg}. ${summary}`);
  });
}

async function busy(label, fn) {
  $("#busyText").textContent = label;
  $("#busy").hidden = false;
  try { await fn(); }
  catch (e) { toast(`⚠️ ${e.message || e}`); }
  finally { $("#busy").hidden = true; }
}

let toastTimer = 0;
function toast(text) {
  const el = $("#toast");
  el.textContent = text;
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), 6000);
}
$("#toast").addEventListener("click", () => $("#toast").classList.remove("show"));

// ---------------------------------------------------------------- navigation

function openDrawer(open) {
  $("#drawer").classList.toggle("open", open);
  $("#drawer").inert = !open;
  $("#scrim").hidden = !open;
  $("#menuBtn").setAttribute("aria-expanded", String(open));
}

function show(view) {
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("active", v.id === `view-${view}`));
  document.querySelectorAll(".drawer-item[data-view]").forEach((b) => b.classList.toggle("active", b.dataset.view === view));
  const title = $("#title");
  title.textContent = { home: "SPENDABLE", recurring: "RECURRING", transactions: "TRANSACTIONS" }[view];
  title.classList.toggle("shimmer", view === "home");
  title.classList.toggle("plain", view !== "home");
  document.documentElement.classList.toggle("broke", view === "home" && lastBroke);
  window.scrollTo(0, 0);
}

$("#menuBtn").addEventListener("click", () => openDrawer(true));
$("#scrim").addEventListener("click", () => openDrawer(false));
$("#drawer").addEventListener("click", (e) => {
  const btn = e.target.closest("button");
  if (!btn) return;
  openDrawer(false);
  if (btn.dataset.view) show(btn.dataset.view);
  if (btn.dataset.action === "import") $("#fileInput").click();
  if (btn.dataset.action === "reset") $("#resetDialog").showModal();
});
$("#dataPill").addEventListener("click", () => $("#fileInput").click());
$("#fileInput").addEventListener("change", (e) => {
  const file = e.target.files?.[0];
  e.target.value = "";
  if (file) importFile(file);
});
$("#resetDialog").addEventListener("close", async () => {
  if ($("#resetDialog").returnValue !== "delete") return;
  await busy("Wiping data…", async () => {
    await clearAll();
    resetResult();
    await refresh();
    toast("All transactions and recurring data deleted.");
  });
});
$("#rerunBtn").addEventListener("click", () => busy("Re-running analysis…", async () => toast(await reanalyze())));
document.querySelectorAll(".chip[data-flow]").forEach((chip) => chip.addEventListener("click", () => {
  flowFilter = chip.dataset.flow;
  document.querySelectorAll(".chip[data-flow]").forEach((c) => c.classList.toggle("active", c === chip));
  renderRecurring();
}));

// ---------------------------------------------------------------- home

const balance = $("#balance");
const sizer = $("#sizer");
let negative = false;

function fitBalance() {
  sizer.textContent = balance.value || balance.placeholder;
  balance.style.width = `${Math.ceil(sizer.getBoundingClientRect().width) + 6}px`;
}

function renderPill() {
  const pill = $("#dataPill");
  if (!transactions.length) {
    pill.textContent = "No data yet · tap to import a bank CSV 📥";
    pill.classList.add("empty");
    return;
  }
  const days = transactions.map((t) => t.day);
  pill.textContent = `📅 ${transactions.length} txns · ${fmtShort(Math.min(...days))} – ${fmtShort(Math.max(...days))} · 🔁 ${recurring.length} recurring`;
  pill.classList.remove("empty");
}

$("#signBtn").addEventListener("click", () => {
  negative = !negative;
  $("#signBtn").textContent = negative ? "-$" : "$";
  haptic(6);
  resetResult();
});
balance.addEventListener("input", () => {
  balance.value = balance.value.replace(/[^\d.,]/g, "");
  $("#inputError").hidden = true;
  fitBalance();
  resetResult();
});
balance.addEventListener("keydown", (e) => { if (e.key === "Enter") { balance.blur(); go(); } });

const goBtn = $("#goBtn");
goBtn.addEventListener("pointerdown", () => goBtn.classList.add("pressed"));
["pointerup", "pointerleave", "pointercancel"].forEach((ev) => goBtn.addEventListener(ev, () => goBtn.classList.remove("pressed")));
goBtn.addEventListener("click", () => { balance.blur(); go(); });

function setPhase(phase, broke = false) {
  goBtn.dataset.phase = phase;
  goBtn.classList.toggle("broke", broke);
  goBtn.innerHTML = `<span class="go-label">${phase === "idle" ? "GO" : phase === "charging" ? "💸" : broke ? "AGAIN 💩" : "AGAIN ↻"}</span>`;
}

let lastBroke = false;
function resetResult() {
  if (goBtn.dataset.phase === "idle") return;
  setPhase("idle");
  $("#result").hidden = true;
  lastBroke = false;
  document.documentElement.classList.remove("broke");
}

async function go() {
  if (goBusy) return;
  const raw = balance.value.trim();
  const parsed = raw ? parseCents(raw) : null;
  if (parsed === null) {
    const err = $("#inputError");
    err.textContent = "Type your available balance first";
    err.hidden = false;
    err.style.animation = "none"; void err.offsetWidth; err.style.animation = "";
    haptic([10, 40, 10]);
    return;
  }
  const cents = negative ? -Math.abs(parsed) : parsed;
  goBusy = true;
  haptic(15);
  store.set("lastBalance", raw);
  store.set("lastNegative", negative ? "1" : "");
  $("#result").hidden = true;
  setPhase("charging");
  const started = performance.now();

  const today = todayEpochDay();
  const txns = transactions.map(toTxn);
  const posted = transactions.filter((t) => !t.pending).map(toTxn);
  const payday = nextPayday(posted, recurring, today);
  const e = estimate(cents, today, recurring, txns, payday);

  // The math is instant; let the charging animation have its moment.
  await new Promise((r) => setTimeout(r, Math.max(0, 1100 - (performance.now() - started))));
  const broke = e.highCents < 0;
  lastBroke = broke;
  setPhase("done", broke);
  document.documentElement.classList.toggle("broke", broke);
  renderResult(e);
  fx.play(broke ? "broke" : "rich");
  haptic(broke ? [30, 60, 30, 60, 80] : [20, 40, 20]);
  goBusy = false;
}

function renderResult(e) {
  const broke = e.highCents < 0;
  const owed = e.items.filter((i) => !i.alreadyPaid);
  const highSum = owed.filter((i) => i.confidence === "HIGH").reduce((s, i) => s + i.amountCents, 0);
  const otherSum = owed.filter((i) => i.confidence !== "HIGH").reduce((s, i) => s + i.amountCents, 0);
  const headline = broke ? "💩 THE FORECAST IS BROWN 💩" : e.lowCents < 0 ? "😬 SPEND CAREFULLY" : "🤑 YOU CAN SPEND";
  const horizon = e.payday
    ? `Bills through ${fmtWeekday(e.horizonEnd - 1)} · next payday ${fmtWeekday(e.payday.day)} (${esc(e.payday.source)}, ${e.payday.cadence})`
    : `Bills through end of ${fmtMonth(e.today)} · no regular payday detected`;
  const conf = { HIGH: "HIGH", MEDIUM: "MED", LOW: "LOW" };

  let html = `<p class="headline">${headline}</p>`;
  if (e.lowCents === e.highCents) {
    html += `<div class="money big ${broke ? "neg" : ""}" data-count="${e.highCents}"></div>`;
  } else {
    html += `<div class="label">WORST CASE</div><div class="money ${e.lowCents < 0 ? "neg" : ""}" data-count="${e.lowCents}"></div>
             <div class="to">to</div>
             <div class="label">BEST CASE</div><div class="money big ${broke ? "neg" : ""}" data-count="${e.highCents}"></div>`;
  }
  html += `<p class="horizon">${horizon}</p>`;
  if (e.dataAgeDays === null) {
    html += `<p class="warn">No transactions imported yet, so nothing was subtracted. Use ☰ → Import bank CSV.</p>`;
  } else if (e.dataAgeDays > 10) {
    html += `<p class="warn">⚠️ Newest imported transaction is ${e.dataAgeDays} days old. Import a fresh CSV so bills that already posted aren't subtracted twice.</p>`;
  }
  if (e.items.length) {
    html += `<div class="sums">
      <div class="row"><span class="grow muted">Starting balance</span><b>${fmt(e.balanceCents)}</b></div>
      <div class="row"><span class="grow muted">High-confidence bills</span><b style="color:var(--lime)">${fmt(-highSum)}</b></div>
      <div class="row"><span class="grow muted">Medium + low confidence</span><b style="color:var(--amber)">${fmt(-otherSum)}</b></div>
    </div><div class="items">`;
    for (const i of e.items) {
      html += `<div class="row ${i.alreadyPaid ? "paid" : ""}">
        <span class="date">${fmtShort(i.dueDay)}</span>
        <span class="grow">${esc(i.name)}</span>
        <span class="chip-conf conf-${i.confidence}">${conf[i.confidence]}</span>
        <b>${i.alreadyPaid ? "paid ✓" : fmt(i.amountCents)}</b>
      </div>`;
    }
    html += `</div>`;
  } else if (e.dataAgeDays !== null) {
    html += `<p class="horizon">No recurring bills expected before then 🎉</p>`;
  }
  const card = $("#result");
  card.className = `result ${broke ? "broke" : ""}`;
  card.innerHTML = html;
  card.hidden = false;
  card.querySelectorAll("[data-count]").forEach((el) => countUp(el, Number(el.dataset.count)));
  setTimeout(() => card.scrollIntoView({ behavior: "smooth", block: "nearest" }), 250);
}

function countUp(el, target) {
  const t0 = performance.now();
  const dur = 1700;
  const tick = (now) => {
    const p = Math.min(1, (now - t0) / dur);
    const eased = 1 - Math.pow(1 - p, 3);
    el.textContent = fmt(Math.round(target * eased));
    if (p < 1) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

// ---------------------------------------------------------------- lists

function renderRecurring() {
  document.querySelectorAll("[data-count]").forEach((el) => {
    if (el.closest(".chip")) el.textContent = recurring.filter((r) => r.flow === el.dataset.count).length;
  });
  const shown = recurring.filter((r) => r.flow === flowFilter).sort((a, b) => a.dayOfMonth - b.dayOfMonth || b.score - a.score);
  const list = $("#recurringList");
  if (!shown.length) {
    list.innerHTML = `<p class="empty-state"><span class="big">🔍</span>Nothing recurring found yet.<br>Import a few months of transactions from the ☰ menu. Three or more months works best.</p>`;
    return;
  }
  const conf = { HIGH: "HIGH", MEDIUM: "MED", LOW: "LOW" };
  list.innerHTML = shown.map((r) => `
    <div class="card">
      <div class="bubble">${r.dayOfMonth}</div>
      <div class="grow">
        <div class="name"><span>${esc(r.displayName)}</span><span class="chip-conf conf-${r.confidence}">${conf[r.confidence]}</span></div>
        <div class="why">${esc(r.rationale)}</div>
        <div class="meta">score ${r.score.toFixed(2)} · last ${fmtLong(r.lastSeenDay)}</div>
      </div>
      <div class="amt ${r.flow === "INCOME" ? "in" : ""}">${fmt(r.expectedAmountCents)}</div>
    </div>`).join("");
}

function renderTransactions() {
  const shown = transactions.slice(0, 400);
  $("#txSummary").textContent = !transactions.length ? "" :
    transactions.length > shown.length ? `Newest ${shown.length} of ${transactions.length} transactions` : `${transactions.length} transactions`;
  $("#txList").innerHTML = !shown.length
    ? `<p class="empty-state"><span class="big">🧾</span>No transactions yet.<br>☰ → Import bank CSV</p>`
    : shown.map((t) => `
      <div class="row">
        <div class="grow"><div>${esc(t.description)}</div>
          <div class="sub">${fmtLong(t.day)}${t.category ? ` · ${esc(t.category)}` : ""}${t.pending ? " · ⏳ pending" : ""}</div></div>
        <b class="${t.amountCents >= 0 ? "in" : ""}">${fmt(t.amountCents)}</b>
      </div>`).join("");
}

// ---------------------------------------------------------------- boot

balance.value = store.get("lastBalance") ?? "";
negative = store.get("lastNegative") === "1";
$("#signBtn").textContent = negative ? "-$" : "$";
document.fonts?.ready.then(fitBalance);
fitBalance();
refresh();

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch(() => { /* offline support is optional */ });
}
