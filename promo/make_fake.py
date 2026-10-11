import csv, random, datetime as dt, sys
random.seed(42)
rows = []
def add(d, desc, cat, amt): rows.append((d, desc, desc.upper(), cat, amt))
start, end = dt.date(2026, 7, 1), dt.date(2026, 10, 10)
day = start
while day <= end:
    if random.random() < 0.55: add(day, "Coffee Shop", "Coffee Shops", -round(random.uniform(3.5, 9.75), 2))
    if day.weekday() == 5: add(day, "Grocery Store", "Groceries", -round(random.uniform(60, 165), 2))
    if random.random() < 0.08: add(day, "Taco Truck", "Fast Food", -round(random.uniform(8, 22), 2))
    if random.random() < 0.07: add(day, "Gas Station", "Gas", -round(random.uniform(30, 55), 2))
    day += dt.timedelta(days=1)
for m in (7, 8, 9):
    add(dt.date(2026, m, 11), "Girls Spa Day in the Pool", "Personal Care", -180.00)
    add(dt.date(2026, m, 12), "Llama Grooming Club", "Pets", -49.99)
    add(dt.date(2026, m, 13), "Streaming Service", "Entertainment", -15.49)
    add(dt.date(2026, m, 15), "Dragon Insurance Co", "Insurance", -88.00)
    add(dt.date(2026, m, 1), "Castle Rent LLC", "Rent", -950.00)
    add(dt.date(2026, m, 12), "Coffee Shop", "Coffee Shops", -25.00)
for m in (8, 9):
    add(dt.date(2026, m, 13), "Grocery Store Delivery+", "Groceries", -12.99)
d = dt.date(2026, 7, 10)
while d <= end:
    add(d, "Totally Real Job Inc", "Paycheck", 2100.00); d += dt.timedelta(days=14)
rows.sort(key=lambda r: r[0], reverse=True)
with open(sys.argv[1], "w", newline="") as f:
    w = csv.writer(f, quoting=csv.QUOTE_MINIMAL)
    w.writerow(["Date", "Description", "Original Description", "Category", "Amount", "Status"])
    for d, desc, orig, cat, amt in rows: w.writerow([d.isoformat(), desc, orig, cat, f"{amt:.2f}", "Posted"])
print(len(rows), "rows")
