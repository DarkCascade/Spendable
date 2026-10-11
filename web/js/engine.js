// The analysis engine: a port of the Android app's engine/ package
// (RecurrenceEngine.kt, PaydayPredictor.kt, DisposableCalculator.kt). Same weights, same thresholds.

import { dayOfMonth, monthIndexOf, dayInMonth, lengthOfMonth, monthIndexToYm, ymdOf } from "./dates.js";

/**
 * @typedef {{ day: number, merchantKey: string, displayName: string, rawDescription: string,
 *             category: string, amountCents: number }} Txn
 * @typedef {"HIGH"|"MEDIUM"|"LOW"} Confidence
 * @typedef {"EXPENSE"|"INCOME"} Direction
 */

const DAY_WINDOW = 4;          // max day-of-month span inside one cluster
const AMOUNT_BAND = 1.3;       // amounts within 30% belong to one group
const NORMAL_PER_MONTH = 2.0;  // two similar bills a month from one merchant is still normal
const HIGH = 0.70, MEDIUM = 0.50, LOW = 0.30;
const EVERYDAY_CATEGORIES = new Set([
  "restaurants", "fast food", "food & dining", "dining", "food & drink", "coffee shops", "groceries",
  "gas", "gas & fuel", "fuel", "alcohol & bars", "rental car & taxi", "convenience stores",
]);

export function median(xs) {
  const s = [...xs].sort((a, b) => a - b);
  const n = s.length;
  return n % 2 === 1 ? s[(n - 1) / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
}

const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
const sortedBy = (xs, key) => xs.map((x, i) => [x, i]).sort((a, b) => key(a[0]) - key(b[0]) || a[1] - b[1]).map((p) => p[0]);

/** @returns {Confidence|null} */
export function levelFor(score, hits) {
  if (score >= HIGH && hits >= 3) return "HIGH";
  if (score >= MEDIUM) return "MEDIUM";
  if (score >= LOW) return "LOW";
  return null;
}

/**
 * Finds recurring charges the simple way: same merchant, same day of the month, month after month.
 * Per merchant, charges cluster by day of month (±2 days), then split by amount so one merchant can
 * carry several bills. Each group is scored on months hit, coverage, amount stability, day
 * tightness and recency, then discounted when it smells like coincidence (busy merchants,
 * 2-hit patterns with a gap, everyday categories).
 * @param {Txn[]} txns posted transactions
 */
export function analyze(txns, asOf = txns.reduce((m, t) => Math.max(m, t.day), -Infinity)) {
  const out = [];
  for (const flow of ["EXPENSE", "INCOME"]) {
    const signed = txns.filter((t) => (flow === "EXPENSE" ? t.amountCents < 0 : t.amountCents > 0));
    const byMerchant = new Map();
    for (const t of signed) {
      if (!byMerchant.has(t.merchantKey)) byMerchant.set(t.merchantKey, []);
      byMerchant.get(t.merchantKey).push(t);
    }
    for (const charges of byMerchant.values()) {
      if (charges.length < 2) continue;
      for (const cluster of clusterByDay(charges)) {
        for (const group of splitByAmount(cluster)) {
          const c = score(group, charges, asOf, flow);
          if (c) out.push(c);
        }
      }
    }
  }
  const flowRank = { EXPENSE: 0, INCOME: 1 };
  return out.map((c, i) => [c, i])
    .sort((a, b) => flowRank[a[0].flow] - flowRank[b[0].flow] || b[0].heuristicScore - a[0].heuristicScore || a[1] - b[1])
    .map((p) => p[0]);
}

function clusterByDay(charges) {
  const clusters = [];
  for (const t of sortedBy(charges, (x) => dayOfMonth(x.day))) {
    const cur = clusters[clusters.length - 1];
    if (cur && dayOfMonth(t.day) - dayOfMonth(cur[0].day) <= DAY_WINDOW) cur.push(t);
    else clusters.push([t]);
  }
  return clusters;
}

function splitByAmount(cluster) {
  const groups = [];
  for (const t of sortedBy(cluster, (x) => Math.abs(x.amountCents))) {
    const cur = groups[groups.length - 1];
    const floor = cur ? Math.abs(cur[0].amountCents) : null;
    if (floor !== null && Math.abs(t.amountCents) <= floor * AMOUNT_BAND + 100) cur.push(t);
    else groups.push([t]);
  }
  return groups;
}

function score(group, merchantCharges, asOf, flow) {
  const byMonth = new Map();
  for (const t of group) {
    const mi = monthIndexOf(t.day);
    if (!byMonth.has(mi)) byMonth.set(mi, []);
    byMonth.get(mi).push(t);
  }
  const hits = byMonth.size;
  if (hits < 2) return null;

  const groupMedian = median(group.map((t) => Math.abs(t.amountCents)));
  // One representative charge per month: the one closest to the group's typical amount.
  const perMonth = [...byMonth.values()]
    .map((inMonth) => inMonth.reduce((best, t) =>
      Math.abs(Math.abs(t.amountCents) - groupMedian) < Math.abs(Math.abs(best.amountCents) - groupMedian) ? t : best))
    .sort((a, b) => a.day - b.day);

  const typicalDay = Math.trunc(median(perMonth.map((t) => dayOfMonth(t.day))));
  const firstMonth = monthIndexOf(perMonth[0].day);
  const asOfMonth = monthIndexOf(asOf);
  const { y: ay, m: am, d: ad } = ymdOf(asOf);
  const asOfLen = lengthOfMonth(ay, am);
  // Only count the current month as "expected" once its billing day (+2 days grace) has passed.
  const dueThisMonth = Math.min(typicalDay, asOfLen);
  const lastExpected = ad >= Math.min(dueThisMonth + 2, asOfLen) ? asOfMonth : asOfMonth - 1;
  const expectedMonths = Math.max(hits, lastExpected - firstMonth + 1);
  const coverage = hits / expectedMonths;

  const amounts = perMonth.map((t) => Math.abs(t.amountCents));
  const amountMedian = median(amounts);
  const spread = (Math.max(...amounts) - Math.min(...amounts)) / amountMedian;
  const amountScore = clamp(1 - spread / 0.3, 0, 1);

  const days = perMonth.map((t) => dayOfMonth(t.day));
  const tightness = clamp(1 - (Math.max(...days) - Math.min(...days)) / 5, 0, 1);

  const lastSeen = perMonth[perMonth.length - 1].day;
  const sinceLast = asOf - lastSeen;
  const recency = clamp(1 - (sinceLast - 35) / 60, 0, 1);

  const countScore = 1 - Math.pow(0.6, hits - 1);

  const lo = Math.min(...amounts) / AMOUNT_BAND;
  const hi = Math.max(...amounts) * AMOUNT_BAND;
  const spanStart = dayInMonth(firstMonth, 1);
  const lastMonth = monthIndexOf(lastSeen);
  const { y: ly, m: lm } = monthIndexToYm(lastMonth);
  const spanEnd = dayInMonth(lastMonth, lengthOfMonth(ly, lm));
  const similar = Math.max(hits, merchantCharges.filter((t) =>
    t.day >= spanStart && t.day <= spanEnd && Math.abs(t.amountCents) >= lo && Math.abs(t.amountCents) <= hi).length);
  const spanMonths = lastMonth - firstMonth + 1;
  const similarPerMonth = similar / spanMonths;
  const busyMerchant = similarPerMonth <= NORMAL_PER_MONTH ? 1 : Math.pow(NORMAL_PER_MONTH / similarPerMonth, 0.8);

  const catCounts = new Map();
  for (const t of group) { const c = t.category.toLowerCase(); catCounts.set(c, (catCounts.get(c) ?? 0) + 1); }
  let category = "", best = -1;
  for (const [c, n] of catCounts) if (n > best) { category = c; best = n; }
  const everyday = EVERYDAY_CATEGORIES.has(category);
  const gappy = hits === 2 && expectedMonths > 2;

  const raw = 0.35 * countScore + 0.20 * coverage + 0.25 * amountScore + 0.10 * recency + 0.10 * tightness;
  const s = raw * busyMerchant * (everyday ? 0.8 : 1) * (gappy ? 0.8 : 1);
  const level = levelFor(s, hits);
  if (!level) return null;

  let rationale = `${hits} of ${expectedMonths} months near day ${typicalDay}`;
  rationale += spread <= 0.02 ? " · same amount" : spread <= 0.15 ? " · steady amount" : " · amount varies";
  if (busyMerchant < 1) rationale += ` · merchant charges ~${Math.round(similarPerMonth)}×/month`;
  if (everyday) rationale += " · everyday category";
  if (sinceLast > 45) rationale += ` · last seen ${sinceLast} days ago`;

  const last = perMonth[perMonth.length - 1];
  return {
    merchantKey: last.merchantKey,
    displayName: last.displayName,
    flow,
    dayOfMonth: typicalDay,
    expectedAmountCents: Math.trunc(median(amounts.slice(-3))),
    hits,
    expectedMonths,
    lastSeenDay: lastSeen,
    score: s,
    confidence: level,
    rationale,
  };
}

// ---------------------------------------------------------------- payday

const MIN_PAYCHECK_CENTS = 100_00;

/** First epoch day strictly after [today] that falls on [dom] (clamped to short months). */
export function nextDayOfMonth(dom, today) {
  for (let mi = monthIndexOf(today); ; mi++) {
    const d = dayInMonth(mi, dom);
    if (d > today) return d;
  }
}

/**
 * Next paycheck: deposit streams with a steady 1–2 week spacing (projected from the last deposit),
 * or monthly deposits the recurrence engine found by day of month.
 * @param {Txn[]} postedTxns
 */
export function nextPayday(postedTxns, recurring, today) {
  if (postedTxns.length === 0) return null;
  const dataEnd = postedTxns.reduce((m, t) => Math.max(m, t.day), -Infinity);
  const options = [];
  const periodicSources = new Set();

  const streams = new Map();
  for (const t of postedTxns) {
    if (t.amountCents < MIN_PAYCHECK_CENTS) continue;
    if (!streams.has(t.merchantKey)) streams.set(t.merchantKey, []);
    streams.get(t.merchantKey).push(t);
  }
  for (const [key, unsorted] of streams) {
    const stream = sortedBy(unsorted, (t) => t.day);
    const dates = [...new Set(stream.map((t) => t.day))].sort((a, b) => a - b).slice(-8);
    if (dates.length < 3) continue;
    const gaps = dates.slice(1).map((d, i) => d - dates[i]);
    const period = Math.trunc(median(gaps));
    if (period < 6 || period > 16) continue;
    if (gaps.filter((g) => Math.abs(g - period) <= 3).length < gaps.length * 0.75) continue;
    // The stream must still have been alive when the data was exported.
    if (dataEnd - dates[dates.length - 1] > period * 2 + 3) continue;
    let next = dates[dates.length - 1] + period;
    while (next <= today) next += period;
    const amount = Math.trunc(median(stream.slice(-4).map((t) => t.amountCents)));
    options.push({ day: next, source: stream[stream.length - 1].displayName, cadence: period <= 8 ? "weekly" : "every 2 weeks", amountCents: amount });
    periodicSources.add(key);
  }

  for (const c of recurring) {
    if (c.flow !== "INCOME" || c.confidence === "LOW" || c.expectedAmountCents < MIN_PAYCHECK_CENTS) continue;
    // A biweekly stream also forms day-of-month clusters; trust the interval projection instead.
    if (periodicSources.has(c.merchantKey)) continue;
    options.push({ day: nextDayOfMonth(c.dayOfMonth, today), source: c.displayName, cadence: `monthly (day ${c.dayOfMonth})`, amountCents: c.expectedAmountCents });
  }
  return options.reduce((best, o) => (best === null || o.day < best.day ? o : best), null);
}

// ---------------------------------------------------------------- calculator

/** Due dates up to this many days ago still count if the data covers them and nothing posted yet. */
const LATE_GRACE_DAYS = 2;

/** Occurrences of [dom] in [from, untilExclusive), clamped to short months. */
export function dueDates(dom, from, untilExclusive) {
  const out = [];
  for (let mi = monthIndexOf(from); dayInMonth(mi, 1) <= untilExclusive; mi++) {
    const d = dayInMonth(mi, dom);
    if (d >= from && d < untilExclusive) out.push(d);
  }
  return out;
}

const confRank = { HIGH: 0, MEDIUM: 1, LOW: 2 };

/**
 * Best case = balance − HIGH bills due before the horizon; worst case also subtracts MEDIUM and LOW.
 * @param {Txn[]} txns every imported row including pending ones, to spot bills already taken this cycle.
 */
export function estimate(balanceCents, today, recurring, txns, payday) {
  const horizonEnd = payday ? payday.day : dayInMonth(monthIndexOf(today) + 1, 1);
  const windowStart = today - LATE_GRACE_DAYS;
  const dataEnd = txns.length ? txns.reduce((m, t) => Math.max(m, t.day), -Infinity) : null;

  const items = [];
  for (const r of recurring) {
    if (r.flow !== "EXPENSE") continue;
    for (const due of dueDates(r.dayOfMonth, windowStart, horizonEnd)) {
      // A due date already behind us only counts if the data covers it (and it's missing).
      if (due < today && (dataEnd === null || dataEnd < due)) continue;
      const alreadyPaid = txns.some((t) =>
        t.merchantKey === r.merchantKey && t.amountCents < 0 &&
        Math.abs(due - t.day) <= 5 &&
        Math.abs(Math.abs(t.amountCents) - r.expectedAmountCents) <= Math.max(r.expectedAmountCents * 0.3, 200));
      items.push({ name: r.displayName, dueDay: due, amountCents: r.expectedAmountCents, confidence: r.confidence, alreadyPaid });
    }
  }
  items.sort((a, b) => a.dueDay - b.dueDay || confRank[a.confidence] - confRank[b.confidence]);

  const owed = items.filter((i) => !i.alreadyPaid);
  const highCents = balanceCents - owed.filter((i) => i.confidence === "HIGH").reduce((s, i) => s + i.amountCents, 0);
  const lowCents = highCents - owed.filter((i) => i.confidence !== "HIGH").reduce((s, i) => s + i.amountCents, 0);
  return { balanceCents, highCents, lowCents, today, horizonEnd, payday, items, dataAgeDays: dataEnd === null ? null : today - dataEnd };
}
