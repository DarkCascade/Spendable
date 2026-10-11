// Bank CSV parsing, merchant ids and dedupe keys.
// A line-for-line port of the Android app's ingest/CsvParser.kt and ingest/Merchants.kt,
// so both apps produce the same transactions (and the same dedupe keys) from the same file.

import { parseIsoDay, epochDayOf, isoOf } from "./dates.js";

export class CsvFormatError extends Error {}

/**
 * @typedef {{ day: number, description: string, originalDescription: string, category: string,
 *             amountCents: number, pending: boolean }} ParsedTransaction
 * day is an epoch day (days since 1970-01-01).
 */

/** @returns {{ transactions: ParsedTransaction[], skippedRows: number, formatName: string, hasCleanMerchantNames: boolean }} */
export function parseCsv(text) {
  const rows = readRows(text.replace(/^﻿/, "")).filter((r) => r.some((c) => c.trim() !== ""));
  if (rows.length === 0) throw new CsvFormatError("The file is empty.");
  const headerIndex = rows.slice(0, 10).findIndex(looksLikeHeader);
  return headerIndex >= 0
    ? parseWithHeader(rows[headerIndex], rows.slice(headerIndex + 1))
    : parseHeaderless(rows);
}

// ---- RFC 4180 reader: quoted fields, escaped quotes, embedded commas and newlines ----

export function readRows(text) {
  const rows = [];
  let row = [];
  let field = "";
  let inQuotes = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQuotes) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') inQuotes = false;
      else field += c;
    } else if (c === '"') inQuotes = true;
    else if (c === ",") { row.push(field); field = ""; }
    else if (c === "\r") { /* skip */ }
    else if (c === "\n") { row.push(field); field = ""; rows.push(row); row = []; }
    else field += c;
  }
  if (field !== "" || row.length > 0) { row.push(field); rows.push(row); }
  return rows.map((r) => r.map((c) => c.trim()));
}

// ---- Header-based layouts ----

const AMOUNT_NAMES = new Set(["amount", "transaction amount", "amount usd"]);
const DEBIT_NAMES = new Set(["debit", "debits", "withdrawal", "withdrawals", "debit amount"]);
const CREDIT_NAMES = new Set(["credit", "credits", "deposit", "deposits", "credit amount"]);

const norm = (h) => h.toLowerCase().replace(/[^a-z ]/g, "").trim();

function looksLikeHeader(row) {
  const names = row.map(norm);
  const hasDate = names.some((n) => n.includes("date"));
  const hasMoney = names.some((n) => AMOUNT_NAMES.has(n) || DEBIT_NAMES.has(n) || CREDIT_NAMES.has(n));
  return hasDate && hasMoney;
}

function parseWithHeader(header, body) {
  const names = header.map(norm);
  const col = (...candidates) => {
    for (const c of candidates) { const i = names.indexOf(c); if (i >= 0) return i; }
    return -1;
  };
  let dateCol = col("date", "transaction date", "trans date", "posting date", "posted date", "post date");
  if (dateCol < 0) dateCol = names.findIndex((n) => n.includes("date"));
  const descCol = col("description", "payee", "merchant", "name", "merchant name", "transaction description", "memo");
  const origCol = col("original description", "raw description", "details description");
  const catCol = col("category", "transaction category");
  const statusCol = col("status", "transaction status");
  const amountCol = names.findIndex((n) => AMOUNT_NAMES.has(n));
  const debitCol = names.findIndex((n) => DEBIT_NAMES.has(n));
  const creditCol = names.findIndex((n) => CREDIT_NAMES.has(n));

  if (descCol < 0) throw new CsvFormatError(`Couldn't find a Description column in: ${header.join(", ")}`);
  if (amountCol < 0 && debitCol < 0 && creditCol < 0) {
    throw new CsvFormatError(`Couldn't find an Amount (or Debit/Credit) column in: ${header.join(", ")}`);
  }

  let skipped = 0;
  const out = [];
  for (const r of body) {
    const cell = (i) => (i >= 0 && i < r.length ? r[i] : "");
    const day = parseDate(cell(dateCol));
    let amount;
    if (amountCol >= 0) amount = parseCents(cell(amountCol));
    else {
      const debit = parseCents(cell(debitCol));
      const credit = parseCents(cell(creditCol));
      amount = debit !== null && debit !== 0 ? -Math.abs(debit) : credit !== null ? Math.abs(credit) : null;
    }
    const desc = cell(descCol);
    if (day === null || amount === null || desc.trim() === "") { skipped++; continue; }
    out.push({
      day,
      description: desc,
      originalDescription: cell(origCol).trim() || desc,
      category: cell(catCol),
      amountCents: amount,
      pending: cell(statusCol).toLowerCase() === "pending",
    });
  }
  const isUsaa = origCol >= 0 && statusCol >= 0;
  return { transactions: out, skippedRows: skipped, formatName: isUsaa ? "USAA" : "Generic (headers)", hasCleanMerchantNames: origCol >= 0 };
}

// ---- Headerless layouts ----

function parseHeaderless(rows) {
  // USAA legacy: status,,date,,description,category,amount
  const first = rows[0];
  const usaaLegacy = first.length >= 7 && ["posted", "pending"].includes(first[0].toLowerCase()) && parseDate(first[2]) !== null;
  let skipped = 0;
  const out = [];
  if (usaaLegacy) {
    for (const r of rows) {
      const day = r.length > 2 ? parseDate(r[2]) : null;
      const amount = r.length > 6 ? parseCents(r[6]) : null;
      const desc = r[4] ?? "";
      if (r.length < 7 || day === null || amount === null || desc.trim() === "") { skipped++; continue; }
      out.push({ day, description: desc, originalDescription: desc, category: r[5], amountCents: amount, pending: r[0].toLowerCase() === "pending" });
    }
    return { transactions: out, skippedRows: skipped, formatName: "USAA (legacy)", hasCleanMerchantNames: false };
  }

  // Generic: first date-looking column, last amount-looking column, longest other text column.
  const dateCol = first.findIndex((c) => parseDate(c) !== null);
  let amountCol = -1;
  for (let i = first.length - 1; i >= 0; i--) if (i !== dateCol && parseCents(first[i]) !== null) { amountCol = i; break; }
  let descCol = -1;
  first.forEach((c, i) => { if (i !== dateCol && i !== amountCol && (descCol < 0 || c.length > first[descCol].length)) descCol = i; });
  if (dateCol < 0 || amountCol < 0 || descCol < 0) {
    throw new CsvFormatError("Unrecognized CSV layout. Expected a header row with Date, Description and Amount columns.");
  }
  for (const r of rows) {
    const day = r[dateCol] !== undefined ? parseDate(r[dateCol]) : null;
    const amount = r[amountCol] !== undefined ? parseCents(r[amountCol]) : null;
    const desc = r[descCol] ?? "";
    if (day === null || amount === null || desc.trim() === "") { skipped++; continue; }
    out.push({ day, description: desc, originalDescription: desc, category: "", amountCents: amount, pending: false });
  }
  return { transactions: out, skippedRows: skipped, formatName: "Generic (no header)", hasCleanMerchantNames: false };
}

// ---- Value parsing ----

const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];

function ymd(y, m, d) {
  if (m < 1 || m > 12 || d < 1) return null;
  const len = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return d > len ? null : epochDayOf(y, m, d);
}

/** Same formats as the Kotlin parser: yyyy-MM-dd, M/d/yyyy, M/d/yy, yyyy/M/d, M-d-yyyy, MMM d, yyyy, d-MMM-yyyy. */
export function parseDate(s) {
  const full = s.trim();
  const t = full.split("T")[0].split(" ")[0];
  if (!t) return null;
  let m;
  if ((m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(t))) return ymd(+m[1], +m[2], +m[3]);
  if ((m = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(t))) return ymd(+m[3], +m[1], +m[2]);
  if ((m = /^(\d{1,2})\/(\d{1,2})\/(\d{2})$/.exec(t))) return ymd(2000 + +m[3], +m[1], +m[2]);
  if ((m = /^(\d{4})\/(\d{1,2})\/(\d{1,2})$/.exec(t))) return ymd(+m[1], +m[2], +m[3]);
  if ((m = /^(\d{1,2})-(\d{1,2})-(\d{4})$/.exec(t))) return ymd(+m[3], +m[1], +m[2]);
  if ((m = /^(\d{1,2})-([A-Za-z]{3})-(\d{4})$/.exec(t))) {
    const mi = MONTHS.indexOf(m[2].toLowerCase());
    return mi < 0 ? null : ymd(+m[3], mi + 1, +m[1]);
  }
  if ((m = /^([A-Za-z]{3}) (\d{1,2}), (\d{4})$/.exec(full))) {
    const mi = MONTHS.indexOf(m[1].toLowerCase());
    return mi < 0 ? null : ymd(+m[3], mi + 1, +m[2]);
  }
  return null;
}

/** "$1,234.56", "-12.34", "(12.34)", "12.34-" → signed integer cents (HALF_UP), or null. */
export function parseCents(s) {
  let t = s.trim().replaceAll("$", "").replaceAll(",", "").replaceAll(" ", "");
  if (!t) return null;
  let negative = false;
  if (t.startsWith("(") && t.endsWith(")")) { negative = true; t = t.slice(1, -1); }
  if (t.endsWith("-")) { negative = !negative; t = t.slice(0, -1); }
  if (t.startsWith("+")) t = t.slice(1);
  if (!/^-?\d+(\.\d+)?$|^-?\.\d+$/.test(t)) return null;
  // Integer arithmetic on the digits, so no float rounding surprises.
  if (t.startsWith("-")) { negative = !negative; t = t.slice(1); }
  const [whole, frac = ""] = t.split(".");
  let cents = Number(whole || "0") * 100 + Number((frac + "00").slice(0, 2));
  if (frac.length > 2 && frac[2] >= "5") cents += 1;
  return negative ? -cents : cents;
}

// ---- Merchant ids ----

const RAW_NOISE_PREFIXES = [
  "POS PURCHASE", "POS DEBIT", "DEBIT CARD PURCHASE", "CHECKCARD", "PURCHASE AUTHORIZED ON",
  "RECURRING PAYMENT", "ACH DEBIT", "ACH CREDIT", "SQ ", "TST ", "DD ", "PP ", "PAYPAL ",
];

const squash = (s) => s.trim().replace(/\s+/g, " ");

/** Cleaned merchant names (USAA's Description) are the id; raw descriptors get store numbers etc. stripped. */
export function merchantKey(description, isCleanName) {
  const upper = description.toUpperCase();
  if (isCleanName) return squash(upper.replace(/[^A-Z0-9&' ]/g, " "));
  let s = squash(upper.replace(/[^A-Z0-9&' ]/g, " "));
  for (const p of RAW_NOISE_PREFIXES) if (s.startsWith(p)) s = s.slice(p.length).trim();
  const tokens = s.split(" ").filter((tok) => tok && !/\d/.test(tok));
  return tokens.slice(0, 3).join(" ") || squash(upper);
}

// ---- Dedupe keys ----

/**
 * Identical rows inside one file are genuinely separate charges (two $5 rides on the same day),
 * so each copy gets an occurrence index: overlapping re-exports produce the same keys and are
 * skipped, while true repeats are kept. Keys match the Android app's TransactionKeys.
 */
export async function assignKeys(rows) {
  const seen = new Map();
  const out = [];
  for (const t of rows) {
    const base = `${isoOf(t.day)}|${t.amountCents}|${squash(t.originalDescription.toUpperCase())}`;
    const n = seen.get(base) ?? 0;
    seen.set(base, n + 1);
    out.push({ id: await sha1(`${base}|${n}`), row: t });
  }
  return out;
}

async function sha1(s) {
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(s));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export { parseIsoDay };
