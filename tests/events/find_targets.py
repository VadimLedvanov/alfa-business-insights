#!/usr/bin/env python3
"""Сверяет события из events.json с CSV кейса и пишет targets.md.

Для каждого события селектор (см. selector_fields в events.json) применяется ко всем
компаниям из df_Uid_data_hack.csv; контрагенты берутся из df_Uid_payment_hack.csv так же,
как CompanyContextService: сумма платежей в обе стороны, собственные платежи исключены,
топ-5. Скрипт падает, если ожидаемая компания не подходит под селектор или
«не затронутая» компания под него подходит — это и есть перепроверка каталога.

Запуск из корня репозитория:
    python3 -I tests/events/find_targets.py
"""
import collections
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CSV_DIR = ROOT / "backend/core/src/main/resources/data/csv"
EVENTS = Path(__file__).with_name("events.json")
TARGETS = Path(__file__).with_name("targets.md")
TOP_COUNTERPARTIES = 5  # company-context.top-counterparties в application.yaml

PRODUCTS = {
    "loan": "CL_PRODUCT_LOA_MARK", "leasing": "CL_PRODUCT_LEA_MARK", "guarantee": "CL_PRODUCT_GAR_MARK",
    "deposit": "CL_PRODUCT_DEP_MARK", "payroll": "CL_PRODUCT_ZPP_MARK", "rko": "CL_PRODUCT_RKO_MARK",
    "factoring": "FACTORING_MARK",
}


def read(name):
    with open(CSV_DIR / name, encoding="utf-8-sig", newline="") as handle:
        yield from csv.DictReader(handle, delimiter=";")


def revenue(row):
    try:
        return float(row["REVENUE_COMPANY"])
    except ValueError:
        return 0.0


def load():
    companies = {row["SYNTHETIC_U"]: row for row in read("df_Uid_data_hack.csv")}
    turnover = collections.defaultdict(collections.Counter)
    for row in read("df_Uid_payment_hack.csv"):
        payer, beneficiary = row["PAYER"], row["BENEFICIARY"]
        try:
            amount = float(row["PAY_AMT"])
        except ValueError:
            continue
        if payer == beneficiary or amount <= 0:
            continue
        turnover[payer][beneficiary] += amount
        turnover[beneficiary][payer] += amount
    top = {cid: [c for c, _ in counter.most_common(TOP_COUNTERPARTIES)] for cid, counter in turnover.items()}
    groups = collections.defaultdict(list)
    for cid, row in companies.items():
        if row["GROUP_ID"]:
            groups[row["GROUP_ID"]].append(cid)
    return companies, top, groups


def okved_in(row, prefixes):
    return any(row["MAINOKVED_CODE"].startswith(p) for p in prefixes)


def fts_with(row, country):
    return row["FTS_FLAG"] == "1.0" and country in row["FTS_COUNTRY_NAME"]


def matches(cid, selector, data):
    companies, top, groups = data
    row = companies[cid]
    if "any_of" in selector:
        return any(matches(cid, sub, data) for sub in selector["any_of"])
    others = [m for m in groups.get(row["GROUP_ID"], []) if m != cid] if row["GROUP_ID"] else []
    checks = {
        "okved_prefix": lambda v: okved_in(row, v),
        "products": lambda v: all(row[PRODUCTS[p]] == "1.0" for p in v),
        "fts_country": lambda v: fts_with(row, v),
        "region_in": lambda v: row["REGION"] in v,
        "min_revenue": lambda v: revenue(row) >= v,
        "employees_in": lambda v: row["AMOUNT_WORKERS_FNS_GRADATION"] in v,
        "counterparty_okved_prefix": lambda v: any(
            c in companies and okved_in(companies[c], v) for c in top.get(cid, [])),
        "group_okved_prefix": lambda v: any(okved_in(companies[m], v) for m in others),
        "group_fts_country": lambda v: any(fts_with(companies[m], v) for m in others),
    }
    return all(checks[key](value) for key, value in selector.items())


def describe(cid, data):
    companies, top, _ = data
    row = companies[cid]
    products = [p for p, col in PRODUCTS.items() if row[col] == "1.0"]
    fts = row["FTS_COUNTRY_NAME"] if row["FTS_FLAG"] == "1.0" else ""
    return (f"| {cid} | {revenue(row) / 1e9:.1f} | {row['MAINOKVED_CODE']} {row['MAINOKVED_NAME'][:45]} "
            f"| {row['REGION']} | {', '.join(products) or '—'} | {fts or '—'} | {row['GROUP_ID'] or '—'} "
            f"| {len(top.get(cid, []))} |")


def main():
    catalog = json.loads(EVENTS.read_text(encoding="utf-8"))
    data = load()
    companies = data[0]
    failures = []
    lines = ["# Целевые компании по событиям", "",
             "Сгенерировано `find_targets.py` из полного CSV кейса. Селекторы — в `events.json`.", ""]
    for item in catalog["events"]:
        hits = [cid for cid in companies if matches(cid, item["selector"], data)]
        hits.sort(key=lambda cid: -revenue(companies[cid]))
        expected = item["expected"]
        for cid in expected["companies"]:
            if cid not in companies:
                failures.append(f"{item['id']}: {cid} нет в CSV")
            elif cid not in hits:
                failures.append(f"{item['id']}: ожидаемая {cid} не подходит под селектор")
        for cid in expected["not_affected"]:
            if cid in hits:
                failures.append(f"{item['id']}: «не затронутая» {cid} подходит под селектор")
        lines += [f"## {item['id']} — {item['event']['title']}", "",
                  f"Фактор: {item['factor']}. Материал банка: {item['bank_material'] or '—'}.",
                  f"Подходит компаний: **{len(hits)}**. Ожидаемые: {', '.join(expected['companies']) or '—'}. "
                  f"Контроль (не затронуты): {', '.join(expected['not_affected']) or '—'}.", "",
                  "| Компания | Выручка, млрд | ОКВЭД | Регион | Продукты банка | ВЭД | Группа | Контрагентов (топ-5) |",
                  "|---|---:|---|---|---|---|---|---:|"]
        shown = list(dict.fromkeys(expected["companies"] + hits[:8]))
        lines += [describe(cid, data) for cid in shown if cid in companies]
        lines.append("")
        print(f"{item['id']}: {len(hits):>5} компаний; ожидаемые {expected['companies']}")
    TARGETS.write_text("\n".join(lines), encoding="utf-8")
    if failures:
        print("\nОШИБКИ:\n  " + "\n  ".join(failures))
        sys.exit(1)
    print(f"\nOK: все ожидания согласуются с CSV; отчёт в {TARGETS.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
