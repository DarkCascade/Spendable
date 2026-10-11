# Spendable

An Android app (built for a Pixel 10) that estimates how much you can actually spend right now.
You give it your available balance, and it subtracts the recurring bills it expects before your
next payday. It learns those bills from bank CSV exports. It never connects to a bank.

## How it works

1. **Import** (☰ → *Import bank CSV*). The CSV is parsed and every row gets a deterministic dedupe
   key: date + amount + raw descriptor + an occurrence index. Re-importing an overlapping range
   (for example "last 30 days" every week) adds only new rows. Two identical charges on the same
   day are both kept. Pending rows are replaced on every import because they change when they post.
2. **Analyze.** Charges are grouped by merchant. Each merchant's charges are clustered by day of
   month (±2 days) and split by amount, so one merchant can have several bills. Each group is
   scored on:
   - months hit
   - coverage since it first appeared
   - amount stability, day tightness, and recency

   The score is then discounted when it looks like coincidence: lots of similar charges per month
   (SunPass reloads), everyday categories (restaurants, gas, groceries), or a 2-hit pattern with a
   gap month. The result is a **HIGH / MEDIUM / LOW** rating.
3. **Go.** The window runs from today until your next payday. Payday comes from biweekly/weekly
   deposit spacing or monthly deposits; if there's no payday it falls back to the end of the month.
   Bills that already show up in the data are skipped.
   - **Best case** = balance − HIGH bills
   - **Worst case** = best case − MEDIUM − LOW bills

### Two databases

| DB file | Contents |
|---|---|
| `transactions.db` | Raw imported transactions (deduped) |
| `recurring.db` | Recurring bills/income by day of month with heuristic score, optional Jev score, final score and confidence. Rebuilt after every import |

### Jev (optional)

☰ → *Jev classifier*: paste a TypeSafe API key, then *Test key* → *Save & re-run analysis*.
Each candidate bill is sent to `POST https://api.typesafe.ai/v1/systemone` as a *state*
(merchant, bank descriptors, category, the monthly charges) with one `noul` question: "is this a
recurring bill?". The final score is `0.6 × heuristic + 0.4 × Jev`. Only candidate bills are sent,
never your balance or full history. Your key stays in app-private storage, excluded from backups.

## Supported CSVs

- **USAA** (current): `Date,Description,Original Description,Category,Amount,Status`
- **USAA** (legacy, headerless): `posted,,MM/DD/YYYY,,DESC,Category,Amount`
- **Generic**: any header with a Date column and either an Amount column or Debit/Credit columns

## Install on your Pixel

Prebuilt APK: `app/build/outputs/apk/release/app-release.apk`

**Option A: USB / wireless debugging (recommended)**

1. On the phone, open Settings → About phone and tap *Build number* 7 times.
2. Then Settings → System → Developer options → turn on *USB debugging*.
3. Plug in the phone and accept the prompt, then run:
   ```
   %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\release\app-release.apk
   ```
   Or open the project in Android Studio and press ▶ with the phone selected.

**Option B: sideload the file.** Copy `app-release.apk` to the phone (Drive, USB, etc.), open it,
and allow "Install unknown apps" for that app when asked.

Getting your data in: on the phone, export transactions from the USAA website as CSV (it lands in
Downloads), then ☰ → *Import bank CSV*.

## Build

Needs Android Studio's bundled JDK and SDK platform 36.1. Then:

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

Stack: AGP 9.1, Kotlin 2.4.21, Jetpack Compose (BOM 2026.06.01), Room 2.8.5 + KSP, DataStore.
The newest AndroidX releases (Compose 1.12, core 1.19, lifecycle 2.11) need compileSdk 37. To move
to them, install SDK 37 and a newer AGP first.

`EngineTest.localSampleSmokeTest` prints the analysis of any `*.csv` in the project root, and is
skipped when none is there. `*.csv` is git-ignored so bank exports never get committed.
