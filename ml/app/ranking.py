"""Персональное ранжирование событий для компании.

Оценка = релевантность × важность × свежесть.
Релевантность складывается из каналов влияния: экономические факторы события × чувствительность компании
(отрасль, группа, контрагенты, продукты банка), страны ВЭД, регион, термины отрасли и смысловая похожесть.
Каждый вклад сопровождается объяснением «почему вам».
"""
import math
import re
from datetime import datetime, timezone
from difflib import SequenceMatcher

MODEL_VERSION = "factors-v1"

FACTORS = {
    "key_rate": ("Ключевая ставка и стоимость кредитов", [
        r"ключев\w* ставк", r"ставк\w* цб", r"денежно-кредитн", r"\bдкп\b", r"ипотек", r"стоимост\w* (кредит|заимствован)",
        r"процентн\w* ставк", r"доходност\w* офз"]),
    "fx": ("Курс рубля", [r"курс\w* (доллар|евро|юан|рубл)", r"\bрубл\w* (укреп|ослаб|подешев|подорож)", r"\bюан", r"девальвац",
                          r"валютн\w* (рын|контрол|выручк)"]),
    "oil_fuel": ("Нефть и топливо", [r"\bнефт", r"бензин", r"дизел", r"топлив", r"нефтепродукт", r"демпфер", r"\bbrent\b",
                                     r"\burals\b", r"\bазс\b"]),
    "energy": ("Энергетика и тарифы", [r"электроэнерг", r"энергосбыт", r"природн\w* газ", r"тариф\w* на (электро|газ|тепло)",
                                       r"тариф\w* жкх"]),
    "metals": ("Металлы и сырьё", [r"\bсталь\b", r"стальн", r"сталелит", r"металлург", r"металлопрокат", r"\bпрокат", r"алюмини", r"никел",
                                   r"\bмед(и|ь)\b", r"железн\w* руд", r"\bзолот"]),
    "construction": ("Строительство и недвижимость", [r"строительств", r"застройщ", r"девелопер", r"ввод\w* жиль", r"цемент",
                                                       r"стройматериал", r"недвижимост", r"жил\w* фонд"]),
    "trade_import": ("Импорт, экспорт, пошлины, санкции", [r"\bпошлин", r"\bимпорт", r"\bэкспорт", r"санкци", r"таможн",
                                                          r"утилизационн\w* сбор", r"параллельн\w* импорт"]),
    "logistics": ("Логистика и перевозки", [r"логистик", r"\bржд\b", r"грузоперевоз", r"перевозк\w* груз", r"\bфрахт",
                                            r"контейнер", r"транзит", r"морск\w* порт"]),
    "tax": ("Налоги", [r"\bналог", r"\bндс\b", r"\bфнс\b", r"акциз", r"\bндпи\b"]),
    "consumer_demand": ("Потребительский спрос", [r"потребительск\w* (спрос|активност|кредит)", r"розничн\w* (продаж|торгов)",
                                                  r"ритейл", r"покупательск"]),
    "inflation": ("Инфляция и экономика", [r"инфляци", r"\bввп\b", r"рецесси", r"рост\w* экономик", r"деловая активност"]),
    "labor": ("Рынок труда и зарплаты", [r"зарплат", r"заработн\w* плат", r"рын\w* труда", r"безработиц", r"дефицит\w* кадров",
                                         r"\bмрот\b", r"мигрант"]),
    "gov_spending": ("Госрасходы и госзаказ", [r"госзаказ", r"госзакуп", r"бюджетн\w* (расход|ассигнован)", r"нацпроект",
                                               r"субсиди", r"госконтракт", r"инфраструктурн"]),
    "agro_food": ("Агро и продовольствие", [r"\bзерн", r"пшениц", r"урожа", r"продовольств", r"сельхоз", r"\bагро", r"удобрен",
                                            r"\bрыб", r"\bмяс", r"молок", r"сахар"]),
    "it_tech": ("IT и электроника", [r"программн\w* обеспечен", r"\bсофт", r"\bит[- ]?(компани|отрасл|сектор)", r"цифров",
                                     r"электроник", r"микросхем", r"полупроводник", r"импортозамещ", r"кибер"]),
}

UP_RE = re.compile(r"повыс|повыш|рост|вырос|увелич|подорож|ускор|ужесточ|ослаб\w* рубл|дорож", re.IGNORECASE)
BARRIER_RE = re.compile(r"пошлин|санкци|ограничен|запрет|утилизационн|квот", re.IGNORECASE)
DOWN_RE = re.compile(r"сниз|сниж|упал|паден|сокращ|подешев|замедл|смягч|отмен|укреп\w* рубл|дешев", re.IGNORECASE)

# Чувствительность отрасли к росту фактора: +1 выигрывает, -1 проигрывает, 0 — затронута, знак неочевиден.
OKVED_SENSITIVITY = {
    "01": {"agro_food": 1, "oil_fuel": -1, "trade_import": 0},
    "03": {"agro_food": 1, "oil_fuel": -1},
    "06": {"oil_fuel": 1, "tax": -1, "trade_import": 0},
    "09": {"oil_fuel": 1},
    "10": {"agro_food": -1, "consumer_demand": 1},
    "11": {"agro_food": -1, "consumer_demand": 1, "tax": -1},
    "19": {"oil_fuel": 1, "tax": -1},
    "20": {"trade_import": 0, "energy": -1},
    "24": {"metals": 1, "energy": -1, "trade_import": 0},
    "25": {"metals": -1, "gov_spending": 1},
    "26": {"it_tech": 1, "trade_import": -1, "fx": -1},
    "27": {"metals": -1, "energy": 0},
    "28": {"metals": -1, "gov_spending": 1},
    "29": {"trade_import": -1, "consumer_demand": 1, "key_rate": -1},
    "35": {"energy": 1, "key_rate": -1},
    "41": {"construction": 1, "key_rate": -1, "metals": -1},
    "42": {"construction": 1, "gov_spending": 1, "metals": -1},
    "43": {"construction": 1, "key_rate": -1},
    "45": {"trade_import": -1, "fx": -1, "consumer_demand": 1, "key_rate": -1},
    "46": {"trade_import": -1, "fx": -1, "logistics": -1},
    "47": {"consumer_demand": 1, "inflation": -1, "labor": -1},
    "49": {"logistics": 1, "oil_fuel": -1},
    "50": {"logistics": 1, "oil_fuel": -1},
    "52": {"logistics": 1},
    "55": {"consumer_demand": 1, "labor": -1},
    "56": {"consumer_demand": 1, "agro_food": -1, "labor": -1},
    "62": {"it_tech": 1, "labor": -1, "tax": -1},
    "63": {"it_tech": 1},
    "64": {"key_rate": 0, "inflation": 0},
    "68": {"construction": 1, "key_rate": -1},
    "71": {"construction": 1, "gov_spending": 1},
    "77": {"key_rate": -1},
    "86": {"gov_spending": 1, "labor": -1},
}
OKVED_PREFIX_SENSITIVITY = {
    "46.71": {"oil_fuel": 0, "trade_import": -1, "tax": -1},
    "46.72": {"metals": 0, "trade_import": -1},
    "46.73": {"construction": 1, "metals": -1},
    "46.2": {"agro_food": 0, "trade_import": 0},
    "46.3": {"agro_food": 0, "trade_import": -1, "fx": -1},
    "46.5": {"it_tech": 0, "trade_import": -1, "fx": -1},
}

# Продукты банка: (фактор, знак при росте фактора, объяснение).
PRODUCT_SENSITIVITY = {
    "hasLoan": ("key_rate", -1, "у компании кредит — дорогие деньги увеличивают расходы на обслуживание долга"),
    "hasLeasing": ("key_rate", -1, "у компании лизинг — ставка влияет на стоимость новых сделок"),
    "hasFactoring": ("key_rate", -1, "у компании факторинг — ставка влияет на стоимость финансирования оборота"),
    "hasDeposit": ("key_rate", 1, "у компании депозиты — высокая ставка увеличивает процентный доход"),
    "hasBankGuarantee": ("gov_spending", 1, "у компании банковские гарантии — работает с госзаказом и контрактами"),
    "hasPayrollProject": ("labor", -1, "у компании зарплатный проект — рост зарплат увеличивает расходы на персонал"),
}

COUNTRIES = {
    "КИТАЙ": r"кита|\bкнр\b", "ТУРЦИЯ": r"турци|турецк", "ИНДИЯ": r"\bинди(я|и|ю|ей)\b|индийск", "ОАЭ": r"\bоаэ\b|эмират",
    "КАЗАХСТАН": r"казахст", "УЗБЕКИСТАН": r"узбекист", "КИРГИЗИЯ": r"киргиз|кыргыз", "БЕЛАРУСЬ": r"беларус|белорус",
    "ГЕРМАНИЯ": r"германи|немецк", "ИТАЛИЯ": r"итали", "ЮЖНАЯ КОРЕЯ": r"южн\w* коре", "ГОНКОНГ": r"гонконг",
    "АЗЕРБАЙДЖАН": r"азербайдж", "ГРУЗИЯ": r"\bгрузи(я|и|ю)\b|грузинск", "ПОЛЬША": r"польш", "ЛИТВА": r"\bлитв",
    "США": r"\bсша\b|американск", "ИРАН": r"\bиран", "ВЬЕТНАМ": r"вьетнам", "ТАЙВАНЬ": r"тайван", "СЕРБИЯ": r"серби",
    "АРМЕНИЯ": r"армени", "ИЗРАИЛЬ": r"израил", "ЯПОНИЯ": r"япони", "ЛАТВИЯ": r"латви",
}

SOURCE_TIERS = {
    "cbr.ru": ("Банк России", 1.0), "government.ru": ("Правительство РФ", 1.0), "minfin.gov.ru": ("Минфин", 1.0),
    "economy.gov.ru": ("Минэкономразвития", 1.0), "interfax.ru": ("Интерфакс", 0.85), "tass.ru": ("ТАСС", 0.85),
    "rbc.ru": ("РБК", 0.85), "kommersant.ru": ("Коммерсантъ", 0.85), "vedomosti.ru": ("Ведомости", 0.85),
    "1prime.ru": ("Прайм", 0.8),
}
URL_RE = re.compile(r"https?://(?:www\.)?([^/\s]+)")
NUMBERS_RE = re.compile(r"\d+[.,]?\d*\s?(%|процент|п\.\s?п|б\.\s?п|млрд|млн|трлн)", re.IGNORECASE)
REGULATOR_RE = re.compile(r"банк россии|\bцб\b|правительств|госдум|\bзакон|постановлен|минфин|минэкономразвит|\bфнс\b|"
                          r"пошлин|санкци|регулятор", re.IGNORECASE)
NOISE_RE = re.compile(r"выбор|оппозици|парламент|протест|митинг|обстрел|\bудар|\bвсу\b|погиб|пострада|\bдтп\b|пожар|"
                      r"сход\w* вагон|авари|землетряс|матч|чемпионат|футбол|хоккей|теннис|турнир|олимпи|спортсмен|концерт|фильм|сериал|умер|скончал|"
                      r"задержан|приговор|похищ|убийств|трамп|байден|пентагон|военн|\bвойн|\bнато\b|\bарми|"
                      r"парти[яи]|президентск|сенат|конгресс|вучич|белый дом|киев|украин|зеленск|фронт", re.IGNORECASE)
WORD_RE = re.compile(r"[а-яёa-z]{5,}", re.IGNORECASE)
INDUSTRY_STOP = {"торговля", "оптовая", "розничная", "деятельность", "деятельности", "прочих", "прочая", "прочие", "услуг",
                 "услуги", "производство", "включая", "кроме", "предоставление", "работы", "аренда", "управление",
                 "собственным", "арендованным", "имуществом", "специализированная", "неспециализированная", "области",
                 "связанная", "другие", "группировки", "изделий", "продукции", "материалов", "оборудования", "средств"}
REGION_STOP = {"республика", "область", "край", "автономный", "округ", "город", "г."}

ROLE_WEIGHTS = {
    "CFO": {"key_rate": 1.5, "fx": 1.4, "tax": 1.3, "inflation": 1.2},
    "TREASURER": {"key_rate": 1.5, "fx": 1.5},
    "ACCOUNTANT": {"tax": 1.6, "labor": 1.2},
    "CEO": {"consumer_demand": 1.4, "trade_import": 1.3, "gov_spending": 1.3, "construction": 1.2},
}

W_COUNTRY, W_REGION, W_TERMS, W_SEMANTIC = 0.8, 0.3, 0.5, 0.6
MAX_FACTOR_WEIGHT, MAX_RELEVANCE = 1.5, 3.0
FRESHNESS_HOURS, FRESHNESS_FLOOR = 72, 0.25
MIN_SCORE = 0.12
NOISE_PENALTY = 0.3

_FACTOR_RE = {fid: re.compile("|".join(patterns), re.IGNORECASE) for fid, (_, patterns) in FACTORS.items()}
_COUNTRY_RE = {name: re.compile(pattern, re.IGNORECASE) for name, pattern in COUNTRIES.items()}
_SENTENCE_RE = re.compile(r"(?<=[.!?…])\s+")


def _sensitivity(code: str | None) -> dict[str, int]:
    if not code:
        return {}
    for prefix in sorted(OKVED_PREFIX_SENSITIVITY, key=len, reverse=True):
        if code.startswith(prefix):
            return OKVED_PREFIX_SENSITIVITY[prefix]
    return OKVED_SENSITIVITY.get(code.split(".")[0].zfill(2), {})


def build_exposure(company: dict) -> dict[str, list[dict]]:
    """Профиль компании → {фактор: [{weight, sign, reason, channel}]}."""
    profile = company.get("profile") or {}
    exposure: dict[str, list[dict]] = {}

    def add(factor, weight, sign, reason, channel):
        exposure.setdefault(factor, []).append({"weight": weight, "sign": sign, "reason": reason, "channel": channel})

    main_code, main_name = profile.get("mainIndustryCode"), profile.get("mainIndustryName") or "основная отрасль"
    for factor, sign in _sensitivity(main_code).items():
        add(factor, 1.0, sign, f"основная отрасль: {main_name}", "direct")

    group = [m for m in company.get("group") or [] if m.get("companyId") != profile.get("companyId")]
    codes: dict[str, list[str]] = {}
    for member in group:
        if member.get("mainIndustryCode"):
            codes.setdefault(member["mainIndustryCode"], []).append(member.get("mainIndustryName") or member["mainIndustryCode"])
    for code, names in codes.items():
        share = len(names) / len(group)
        if share < 0.05:
            continue
        for factor, sign in _sensitivity(code).items():
            add(factor, 0.7 * share, sign, f"компании группы: {names[0]} ({share:.0%} группы)", "group")

    for cp in company.get("counterparties") or []:
        share = float(cp.get("paymentShare") or 0)
        if share < 0.03 or not cp.get("mainIndustryCode"):
            continue
        outgoing, incoming = float(cp.get("outgoingPaymentAmount") or 0), float(cp.get("incomingPaymentAmount") or 0)
        role = "получатель платежей" if outgoing >= incoming else "плательщик"
        for factor, sign in _sensitivity(cp["mainIndustryCode"]).items():
            add(factor, min(0.8, 0.2 + 2 * share), 0, f"контрагент ({role}): {cp.get('mainIndustryName')}, {share:.0%} оборота",
                "counterparty")

    for flag, (factor, sign, reason) in PRODUCT_SENSITIVITY.items():
        if profile.get(flag):
            add(factor, 0.9, sign, reason, "products")
    if profile.get("hasForeignTrade") or profile.get("foreignTradeCountries"):
        add("fx", 0.8, -1, "у компании внешнеэкономическая деятельность", "trade")
        add("trade_import", 0.8, -1, "у компании внешнеэкономическая деятельность", "trade")
    return exposure


def _countries(profile: dict) -> list[str]:
    raw = profile.get("foreignTradeCountries") or ""
    return [c.strip().upper() for c in raw.split(",") if c.strip()]


def _region_stem(profile: dict) -> str | None:
    words = [w for w in (profile.get("region") or "").lower().split() if w not in REGION_STOP]
    if not words:
        return None
    word = max(words, key=len)
    return word[:-2] if len(word) > 6 else word[:-1]


def _industry_terms(company: dict) -> set[str]:
    profile = company.get("profile") or {}
    names = [profile.get("mainIndustryName") or ""]
    terms = set()
    for name in names:
        for word in WORD_RE.findall(name.lower()):
            if word not in INDUSTRY_STOP:
                terms.add(word[:max(5, len(word) - 3)])
    return terms


def _event_factors(title: str, text: str) -> dict[str, dict]:
    """Факторы события: сила упоминания и направление (рост/снижение) по ближайшим глаголам."""
    found = {}
    title_low = title.lower()
    sentences = _SENTENCE_RE.split(f"{title}. {text[:4000]}")
    for fid, rx in _FACTOR_RE.items():
        hits = [s for s in sentences if rx.search(s)]
        if not hits:
            continue
        in_title = bool(rx.search(title_low))
        strength = min(1.0, 0.45 + (0.35 if in_title else 0) + 0.1 * min(len(hits), 3))
        up = sum(1 for s in hits if UP_RE.search(s))
        down = sum(1 for s in hits if DOWN_RE.search(s))
        direction = 1 if up > down else -1 if down > up else 0
        if fid == "trade_import":
            barrier = [s for s in hits if BARRIER_RE.search(s)]
            direction = 0 if not barrier else (-1 if any(DOWN_RE.search(s) for s in barrier) else 1)
        found[fid] = {"strength": strength, "direction": direction}
    return found


def _source(text: str) -> tuple[str | None, float]:
    for domain in URL_RE.findall(text):
        for known, (name, tier) in SOURCE_TIERS.items():
            if domain.endswith(known):
                return name, tier
    return None, 0.75


def _age_hours(published, now: datetime) -> float:
    if not published:
        return 0.0
    if isinstance(published, (int, float)):
        moment = datetime.fromtimestamp(published, tz=timezone.utc)
    else:
        moment = datetime.fromisoformat(str(published).replace("Z", "+00:00"))
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=timezone.utc)
    return max((now - moment).total_seconds() / 3600, 0.0)


def _company_text(company: dict) -> str:
    profile = company.get("profile") or {}
    parts = [profile.get("mainIndustryName") or ""]
    parts += [m.get("mainIndustryName") or "" for m in (company.get("group") or [])[:10]]
    parts += [c.get("mainIndustryName") or "" for c in (company.get("counterparties") or [])]
    if profile.get("foreignTradeCountries"):
        parts.append("внешняя торговля: " + profile["foreignTradeCountries"])
    return "; ".join(dict.fromkeys(p for p in parts if p))


def _semantic_percentiles(company: dict, candidates: list[dict]) -> list[float]:
    """Смысловая похожесть как перцентиль среди кандидатов (устойчиво к шкале модели); 0, если модели нет."""
    try:
        from app.pdf import _model, embed
    except Exception:
        return [0.0] * len(candidates)
    if _model is None or not candidates:
        return [0.0] * len(candidates)
    vectors = embed([f"query: {_company_text(company)}"] + [f"passage: {c['title']}. {c['text'][:1000]}" for c in candidates])
    query = vectors[0]
    sims = [sum(a * b for a, b in zip(query, v)) for v in vectors[1:]]
    order = sorted(range(len(sims)), key=lambda i: sims[i])
    percentile = [0.0] * len(sims)
    for rank, index in enumerate(order):
        percentile[index] = rank / max(len(sims) - 1, 1)
    return percentile


def _score(candidate: dict, exposure: dict, countries: list[str], region: str | None, terms: set[str],
           semantic: float, role: str | None, now: datetime) -> dict:
    title, text = candidate.get("title") or "", candidate.get("text") or ""
    low = f"{title}\n{text}".lower()
    contributions, factors = [], []
    role_weights = ROLE_WEIGHTS.get((role or "").upper(), {})

    for fid, info in _event_factors(title, text).items():
        if fid not in exposure:
            continue
        best = max(exposure[fid], key=lambda e: e["weight"])
        weights = sorted((e["weight"] for e in exposure[fid]), reverse=True)
        weight = min(weights[0] + 0.3 * sum(weights[1:]), MAX_FACTOR_WEIGHT)
        value = weight * info["strength"] * role_weights.get(fid, 1.0)
        signed = sum(e["sign"] * e["weight"] for e in exposure[fid])
        company_sign = 0 if abs(signed) < 0.25 else (1 if signed > 0 else -1)
        effect = info["direction"] * company_sign
        arrow = {1: " ↑", -1: " ↓", 0: ""}[info["direction"]]
        consequence = {1: " — возможность", -1: " — риск", 0: ""}[effect]
        contributions.append((value, f"{FACTORS[fid][0]}{arrow} → {best['reason']}{consequence}"))
        factors.append({"id": fid, "name": FACTORS[fid][0], "direction": info["direction"], "effect": effect,
                        "contribution": round(value, 3), "channel": best["channel"]})

    matched_countries = [c for c in countries if c in _COUNTRY_RE and _COUNTRY_RE[c].search(low)]
    if matched_countries:
        contributions.append((W_COUNTRY, "упоминается страна ВЭД компании: " + ", ".join(matched_countries)))
    if region and region in low:
        contributions.append((W_REGION, "событие в регионе компании"))
    term_hits = sorted({t for t in terms if t in low})
    if term_hits:
        contributions.append((W_TERMS * min(1.0, 0.5 * len(term_hits)), "совпадает с отраслью компании: " + ", ".join(term_hits[:3])))
    if semantic > 0:
        contributions.append((W_SEMANTIC * semantic ** 2, f"близко по смыслу к профилю компании ({semantic:.0%} кандидатов ниже)"))

    ordered = sorted((v for v, _ in contributions), reverse=True)
    decay = [1.0, 0.6, 0.35]
    relevance = min(sum(v * (decay[i] if i < len(decay) else 0.2) for i, v in enumerate(ordered)), MAX_RELEVANCE)
    source_name, importance = _source(text)
    if NUMBERS_RE.search(low):
        importance += 0.1
    if REGULATOR_RE.search(low):
        importance += 0.15
    noise = bool(NOISE_RE.search(title.lower()))
    if noise:
        importance *= NOISE_PENALTY
    freshness = FRESHNESS_FLOOR + (1 - FRESHNESS_FLOOR) * math.exp(-_age_hours(candidate.get("publishedAt"), now) / FRESHNESS_HOURS)

    total_effect = sum(f["effect"] * f["contribution"] for f in factors)
    effect = "opportunity" if total_effect > 0.05 else "risk" if total_effect < -0.05 else (
        "mixed" if any(f["effect"] for f in factors) else "unknown")
    why = [reason for _, reason in sorted(contributions, key=lambda c: -c[0])[:4]]
    if source_name:
        why.append(f"источник: {source_name}")
    return {"id": candidate["id"], "score": round(relevance * importance * freshness, 4), "effect": effect,
            "factors": sorted(factors, key=lambda f: -f["contribution"]), "why": why,
            "parts": {"relevance": round(relevance, 3), "importance": round(importance, 3), "freshness": round(freshness, 3)},
            "noise": noise, "_title": title, "_general": round(importance * freshness, 4)}


def _similar(a: str, b: str) -> bool:
    return SequenceMatcher(None, a.lower(), b.lower()).ratio() > 0.75


def rank(company: dict, candidates: list[dict], top_k: int = 3, role: str | None = None) -> dict:
    now = datetime.now(timezone.utc)
    candidates = [c for c in candidates if c.get("id") and (c.get("title") or c.get("text"))]
    exposure = build_exposure(company)
    profile = company.get("profile") or {}
    countries, region, terms = _countries(profile), _region_stem(profile), _industry_terms(company)
    semantic = _semantic_percentiles(company, candidates)
    scored = [_score(c, exposure, countries, region, terms, s, role, now) for c, s in zip(candidates, semantic)]

    selected, used_factors = [], {}
    pool = sorted((s for s in scored if s["score"] >= MIN_SCORE), key=lambda s: -s["score"])
    while pool and len(selected) < top_k:
        for item in pool:
            main = item["factors"][0]["id"] if item["factors"] else None
            item["_adjusted"] = item["score"] * (0.6 ** used_factors.get(main, 0) if main else 1.0)
        pool.sort(key=lambda s: -s["_adjusted"])
        best = pool.pop(0)
        if any(_similar(best["_title"], s["_title"]) for s in selected):
            continue
        selected.append(best)
        if best["factors"]:
            used_factors[best["factors"][0]["id"]] = used_factors.get(best["factors"][0]["id"], 0) + 1

    if len(selected) < top_k:
        chosen = {s["id"] for s in selected}
        general = sorted((s for s in scored if s["id"] not in chosen and not s["noise"]), key=lambda s: -s["_general"])
        for item in general:
            if len(selected) >= top_k:
                break
            if any(_similar(item["_title"], s["_title"]) for s in selected):
                continue
            item["why"] = ["прямой связи с профилем не найдено — показано как значимое общеэкономическое событие"] + \
                [w for w in item["why"] if w.startswith("источник")]
            item["effect"] = "unknown"
            selected.append(item)

    items = [{k: v for k, v in s.items() if not k.startswith("_") and k != "noise"} for s in selected]
    return {"modelVersion": MODEL_VERSION, "items": items}
