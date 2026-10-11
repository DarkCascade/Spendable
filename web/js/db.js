// On-device storage: two IndexedDB databases, mirroring the Android app's two Room databases.
//   spendable-transactions → raw imported transactions, keyed by dedupe id
//   spendable-recurring    → recurring bills/income by day of month with confidence (rebuilt on each import)
// Nothing here ever leaves the phone.

function open(name, upgrade) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(name, 1);
    req.onupgradeneeded = () => upgrade(req.result);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

const txDb = open("spendable-transactions", (db) => {
  const s = db.createObjectStore("transactions", { keyPath: "id" });
  s.createIndex("merchantKey", "merchantKey");
  s.createIndex("day", "day");
});

const recDb = open("spendable-recurring", (db) => {
  db.createObjectStore("recurring", { keyPath: "id", autoIncrement: true });
});

const done = (tx) => new Promise((resolve, reject) => {
  tx.oncomplete = () => resolve();
  tx.onerror = () => reject(tx.error);
  tx.onabort = () => reject(tx.error);
});

const all = (store) => new Promise((resolve, reject) => {
  const req = store.getAll();
  req.onsuccess = () => resolve(req.result);
  req.onerror = () => reject(req.error);
});

/** Newest first, like the Android DAO's ORDER BY epochDay DESC. */
export async function allTransactions() {
  const db = await txDb;
  const rows = await all(db.transaction("transactions").objectStore("transactions"));
  return rows.sort((a, b) => b.day - a.day || a.description.localeCompare(b.description));
}

/**
 * Pending rows change shape when they post, so they're replaced on every import rather than
 * deduped. Everything else is insert-if-absent. Returns how many rows were new.
 */
export async function importRows(rows) {
  const db = await txDb;
  const tx = db.transaction("transactions", "readwrite");
  const store = tx.objectStore("transactions");
  const cursorReq = store.openCursor();
  await new Promise((resolve, reject) => {
    cursorReq.onsuccess = () => {
      const c = cursorReq.result;
      if (!c) return resolve();
      if (c.value.pending) c.delete();
      c.continue();
    };
    cursorReq.onerror = () => reject(cursorReq.error);
  });
  let added = 0;
  for (const row of rows) {
    const req = store.add(row);
    req.onsuccess = () => { added++; };
    // Duplicate key = already imported. Swallow it so the rest of the batch still commits.
    req.onerror = (e) => { e.preventDefault(); e.stopPropagation(); };
  }
  await done(tx);
  return added;
}

export async function allRecurring() {
  const db = await recDb;
  return all(db.transaction("recurring").objectStore("recurring"));
}

/** Analysis output is always recomputed from scratch, so replace atomically. */
export async function replaceRecurring(rows) {
  const db = await recDb;
  const tx = db.transaction("recurring", "readwrite");
  const store = tx.objectStore("recurring");
  store.clear();
  for (const r of rows) store.add(r);
  await done(tx);
}

export async function clearAll() {
  const [a, b] = await Promise.all([txDb, recDb]);
  const t1 = a.transaction("transactions", "readwrite");
  t1.objectStore("transactions").clear();
  const t2 = b.transaction("recurring", "readwrite");
  t2.objectStore("recurring").clear();
  await Promise.all([done(t1), done(t2)]);
}

/** Ask Safari not to evict our data under storage pressure (home-screen apps are usually granted this). */
export async function requestPersistence() {
  try { return (await navigator.storage?.persist?.()) ?? false; } catch { return false; }
}
