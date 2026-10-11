// Run with:  node --test web/test/*.test.mjs
// Mirrors the Android app's CsvParserTest.kt and EngineTest.kt.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { parseCsv, readRows, parseCents, parseDate, merchantKey, assignKeys } from "../js/csv.js";
import { analyze, nextPayday, estimate } from "../js/engine.js";
import { parseIsoDay as d, isoOf } from "../js/dates.js";

const usaa = [
  "Date,Description,Original Description,Category,Amount,Status",
  '2026-03-31,"Winn-Dixie","WINN-DIXIE   #07 11092   SPRING HILL  FL",Groceries,-192.69,Posted',
  '2026-03-30,"Uber","PAYPAL           INST XFER  ***********UBER",Rental Car & Taxi,-5.00,Posted',
  '2026-03-30,"Uber","PAYPAL           INST XFER  ***********UBER",Rental Car & Taxi,-5.00,Posted',
  '2026-03-27,"Payroll","ACME PAYROLL",Paycheck,600.00,Posted',
  '2026-04-01,"Netflix, Inc.","NETFLIX.COM",Entertainment,-15.49,Pending',
].join("\n");

test("parses USAA export", () => {
  const r = parseCsv(usaa);
  assert.equal(r.formatName, "USAA");
  assert.equal(r.transactions.length, 5);
  assert.ok(r.hasCleanMerchantNames);
  assert.equal(r.transactions[0].day, d("2026-03-31"));
  assert.equal(r.transactions[0].amountCents, -19269);
  assert.equal(r.transactions[3].amountCents, 60000);
  assert.equal(r.transactions[4].description, "Netflix, Inc.");
  assert.ok(r.transactions[4].pending);
});

test("identical rows in one file are kept, re-imports dedupe", async () => {
  const first = (await assignKeys(parseCsv(usaa).transactions)).map((k) => k.id);
  assert.equal(new Set(first).size, 5);
  const overlap = usaa.split("\n").filter((_, i) => i === 0 || i === 2 || i === 3).join("\n");
  const second = (await assignKeys(parseCsv(overlap).transactions)).map((k) => k.id);
  assert.deepEqual(new Set(second), new Set([first[1], first[2]]));
});

test("parses USAA legacy headerless", () => {
  const r = parseCsv('posted,,03/02/2026,,"NETFLIX.COM","Entertainment",-15.49\npending,,03/03/2026,,"SHELL","Gas",-40.00');
  assert.equal(r.formatName, "USAA (legacy)");
  assert.equal(r.transactions[0].day, d("2026-03-02"));
  assert.ok(r.transactions[1].pending);
});

test("debit/credit columns", () => {
  const r = parseCsv("Transaction Date,Posted Date,Card No.,Description,Category,Debit,Credit\n2026-03-01,2026-03-02,1234,SPOTIFY,Music,10.99,\n2026-03-05,2026-03-05,1234,REFUND,Other,,5.00");
  assert.deepEqual(r.transactions.map((t) => t.amountCents), [-1099, 500]);
});

test("amounts, dates, quoting, merchant keys", () => {
  assert.equal(parseCents("($1,234.56)"), -123456);
  assert.equal(parseCents("12.34-"), -1234);
  assert.equal(parseCents("+10"), 1000);
  assert.equal(parseCents("1.235"), 124);
  assert.equal(parseDate("1/5/26"), d("2026-01-05"));
  assert.equal(parseDate("Mar 2, 2026"), d("2026-03-02"));
  assert.deepEqual(readRows('a,"b, ""c""",d\r\n1,2,3'), [["a", 'b, "c"', "d"], ["1", "2", "3"]]);
  assert.equal(merchantKey("Netflix, Inc.", true), "NETFLIX INC");
  assert.equal(merchantKey("WINN-DIXIE   #07 11092   SPRING HILL  FL", false), "WINN DIXIE SPRING");
  assert.equal(merchantKey("SQ *BLUE BOTTLE 123", false), merchantKey("SQ *BLUE BOTTLE 987", false));
});

const t = (date, name, cents, category = "") => ({ day: d(date), merchantKey: name.toUpperCase(), displayName: name, rawDescription: name, category, amountCents: cents });
const monthly = (name, dom, cents, months = [1, 2, 3]) => months.map((m) => t(`2026-${String(m).padStart(2, "0")}-${String(dom).padStart(2, "0")}`, name, cents));
const find = (cs, name) => cs.find((c) => c.displayName === name);
const MAR31 = d("2026-03-31");

test("steady monthly charge is HIGH", () => {
  const c = find(analyze(monthly("Netflix", 15, -1549), MAR31), "Netflix");
  assert.equal(c.confidence, "HIGH");
  assert.equal(c.dayOfMonth, 15);
  assert.equal(c.expectedAmountCents, 1549);
});

test("two months caps at MEDIUM", () => {
  assert.equal(find(analyze(monthly("Apple", 24, -999, [2, 3]), MAR31), "Apple").confidence, "MEDIUM");
});

test("one merchant, two bills split by amount", () => {
  const found = analyze([...monthly("Rentokil", 23, -4737), ...monthly("Rentokil", 24, -14933)], MAR31);
  assert.deepEqual(new Set(found.map((c) => c.expectedAmountCents)), new Set([4737, 14933]));
});

test("frequent same-amount merchant is demoted", () => {
  const reloads = [1, 2, 3].flatMap((m) => [2, 6, 11, 17, 23, 28].map((dd) => t(`2026-0${m}-${String(dd).padStart(2, "0")}`, "SunPass", -1000)));
  const c = find(analyze(reloads, MAR31), "SunPass");
  assert.ok(!c || c.confidence === "LOW", `was ${c?.confidence}`);
});

test("varying amounts are not recurring", () => {
  assert.equal(find(analyze([t("2026-01-12", "Amazon", -14714), t("2026-02-12", "Amazon", -1129), t("2026-03-12", "Amazon", -6816)], MAR31), "Amazon"), undefined);
});

test("biweekly payday is projected", () => {
  const pay = ["2026-01-08", "2026-01-22", "2026-02-05", "2026-02-19", "2026-03-05", "2026-03-19"].map((x) => t(x, "Webstaurant", 415085));
  const p = nextPayday(pay, [], d("2026-03-25"));
  assert.equal(isoOf(p.day), "2026-04-02");
  assert.equal(p.cadence, "every 2 weeks");
});

const rec = (name, dom, cents, confidence) => ({ merchantKey: name.toUpperCase(), displayName: name, flow: "EXPENSE", dayOfMonth: dom, expectedAmountCents: cents, confidence });

test("estimate range uses confidence tiers", () => {
  const recurring = [rec("Loan", 12, 58432, "HIGH"), rec("Gym", 20, 2900, "MEDIUM"), rec("Maybe", 14, 1000, "LOW"), rec("NextCycle", 25, 99999, "HIGH"), rec("AlreadyPaid", 10, 4000, "HIGH")];
  const e = estimate(200000, d("2026-04-10"), recurring, [t("2026-04-10", "AlreadyPaid", -4000)], { day: d("2026-04-24") });
  assert.equal(e.highCents, 200000 - 58432);
  assert.equal(e.lowCents, 200000 - 58432 - 2900 - 1000);
  assert.ok(e.items.find((i) => i.name === "AlreadyPaid").alreadyPaid);
  assert.ok(!e.items.some((i) => i.name === "NextCycle"));
});

test("no payday → end of month, short months clamp", () => {
  const e = estimate(10000, d("2026-02-20"), [rec("Rent", 31, 5000, "HIGH")], [], null);
  assert.equal(isoOf(e.horizonEnd), "2026-03-01");
  assert.equal(isoOf(e.items[0].dueDay), "2026-02-28");
  assert.equal(e.highCents, 5000);
});

// Parity with the Android engine on the real export (never committed; skipped when absent).
const root = join(import.meta.dirname, "..", "..", "..");
const sample = readdirSync(root).find((f) => f === "bill-pay_jan1-mar31.csv");
test("matches Android tiers on the Jan–Mar sample", { skip: !sample && "no local sample CSV" }, () => {
  const parsed = parseCsv(readFileSync(join(root, sample), "utf8"));
  const txns = parsed.transactions.filter((x) => !x.pending).map((x) => ({
    day: x.day, merchantKey: merchantKey(x.description, parsed.hasCleanMerchantNames), displayName: x.description,
    rawDescription: x.originalDescription, category: x.category, amountCents: x.amountCents,
  }));
  const cs = analyze(txns);
  const exp = cs.filter((c) => c.flow === "EXPENSE");
  const tiers = ["HIGH", "MEDIUM", "LOW"].map((l) => exp.filter((c) => c.confidence === l).length);
  assert.deepEqual(tiers, [26, 12, 12]);
  assert.equal(cs.filter((c) => c.flow === "INCOME").length, 5);
  const p = nextPayday(txns, cs, MAR31);
  assert.equal(isoOf(p.day), "2026-04-02");
  assert.equal(p.source, "WebstaurantStore");
});
