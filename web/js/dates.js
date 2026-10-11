// Calendar helpers on "epoch days" (whole days since 1970-01-01, UTC), so date math never
// trips over time zones or daylight saving.

const MS_PER_DAY = 86_400_000;

export const epochDayOf = (y, m, d) => Math.round(Date.UTC(y, m - 1, d) / MS_PER_DAY);

/** {y, m, d} for an epoch day (m is 1-based). */
export function ymdOf(day) {
  const dt = new Date(day * MS_PER_DAY);
  return { y: dt.getUTCFullYear(), m: dt.getUTCMonth() + 1, d: dt.getUTCDate() };
}

export const dayOfMonth = (day) => ymdOf(day).d;

export const lengthOfMonth = (y, m) => new Date(Date.UTC(y, m, 0)).getUTCDate();

/** Months since year 0, used like Java's YearMonth: monthIndex(2026-03-15) - monthIndex(2026-01-02) = 2. */
export const monthIndexOf = (day) => { const { y, m } = ymdOf(day); return y * 12 + (m - 1); };

export const monthIndexToYm = (mi) => ({ y: Math.floor(mi / 12), m: (mi % 12) + 1 });

/** Epoch day of [dom] in month [mi], clamped to short months (the 31st in February → the 28th/29th). */
export function dayInMonth(mi, dom) {
  const { y, m } = monthIndexToYm(mi);
  return epochDayOf(y, m, Math.min(dom, lengthOfMonth(y, m)));
}

export const isoOf = (day) => {
  const { y, m, d } = ymdOf(day);
  return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
};

export const parseIsoDay = (s) => { const [y, m, d] = s.split("-").map(Number); return epochDayOf(y, m, d); };

/** Today in the phone's own time zone. */
export function todayEpochDay(now = new Date()) {
  return epochDayOf(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

const SHORT = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", timeZone: "UTC" });
const WEEKDAY = new Intl.DateTimeFormat("en-US", { weekday: "short", month: "short", day: "numeric", timeZone: "UTC" });
const LONG = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" });
const MONTH = new Intl.DateTimeFormat("en-US", { month: "long", timeZone: "UTC" });

export const fmtShort = (day) => SHORT.format(new Date(day * MS_PER_DAY));
export const fmtWeekday = (day) => WEEKDAY.format(new Date(day * MS_PER_DAY)).replace(",", "");
export const fmtLong = (day) => LONG.format(new Date(day * MS_PER_DAY));
export const fmtMonth = (day) => MONTH.format(new Date(day * MS_PER_DAY));
