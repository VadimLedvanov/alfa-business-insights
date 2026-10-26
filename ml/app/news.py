import hashlib
import html
import json
import re
import time
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse
from urllib.robotparser import RobotFileParser

import feedparser
import httpx
import trafilatura

SOURCES = {
    "cbr":        {"url": "https://www.cbr.ru/rss/RssPress", "tier": 1},
    "interfax":   {"url": "https://www.interfax.ru/rss.asp", "tier": 2},
    "tass":       {"url": "https://tass.ru/rss/v2.xml", "tier": 2},
    "rbc":        {"url": "https://rssexport.rbc.ru/rbcnews/news/30/full.rss", "tier": 2},
    "kommersant": {"url": "https://www.kommersant.ru/RSS/news.xml", "tier": 2},
}

DATA_FILE = Path(__file__).resolve().parent.parent / "data" / "news.jsonl"
HEADERS = {"User-Agent": "AlfaBusinessInsightsBot/0.1 (hackathon prototype)"}
TIMEOUT = 15
LIMIT_PER_SOURCE = 50
DELAY_BETWEEN_REQUESTS = 0.5
MIN_TEXT_LENGTH = 200
NEAR_DUPLICATE_THRESHOLD = 0.85
NEAR_DUPLICATE_WINDOW = timedelta(hours=48)

NON_BUSINESS_WORDS = [
    "футбол", "хоккей", "матч", "чемпионат", "олимпиад", "спортсмен", "тренер",
    "дтп", "погиб", "пострадавш", "убийств", "пожар в жилом",
    "концерт", "фильм", "сериал", "актер", "актрис", "певец", "певица",
    "погода", "гороскоп", "рецепт",
]
NON_BUSINESS_CATEGORIES = {"спорт", "происшествия", "культура", "кино", "музыка", "шоу-бизнес", "стиль"}

TAG_RE = re.compile(r"<[^>]+>")
SPACES_RE = re.compile(r"[ \t\u00a0\u2009\u202f]+")
TRASH_LINE_RE = re.compile(
    r"^(читайте также|читайте нас|подписывайтесь|реклама|фото:|видео:|источник фото|"
    r"поделиться|материалы по теме|теги:)", re.IGNORECASE)
NOT_LETTERS_RE = re.compile(r"[^\w\s]")


def clean_text(text: str) -> str:
    if not text:
        return ""
    text = html.unescape(text)
    text = TAG_RE.sub(" ", text)
    lines, seen = [], set()
    for line in text.splitlines():
        line = SPACES_RE.sub(" ", line).strip()
        if not line or TRASH_LINE_RE.match(line) or line in seen:
            continue
        seen.add(line)
        lines.append(line)
    return "\n".join(lines)


def canonical_url(url: str) -> str:
    parts = urlparse(url.strip())
    query = [(k, v) for k, v in parse_qsl(parts.query) if not k.lower().startswith(("utm_", "from", "ref"))]
    return urlunparse(parts._replace(netloc=parts.netloc.lower(), query=urlencode(query), fragment=""))


def make_id(url: str) -> str:
    return hashlib.sha1(url.encode("utf-8")).hexdigest()[:16]


def parse_date(entry) -> datetime | None:
    parsed = entry.get("published_parsed") or entry.get("updated_parsed")
    if not parsed:
        return None
    return datetime(*parsed[:6], tzinfo=timezone.utc)


def is_business(title: str, categories: list[str]) -> bool:
    if any(c.strip().lower() in NON_BUSINESS_CATEGORIES for c in categories):
        return False
    low = title.lower()
    return not any(word in low for word in NON_BUSINESS_WORDS)


def normalize_title(title: str) -> str:
    return " ".join(NOT_LETTERS_RE.sub(" ", title.lower()).split())


def is_near_duplicate(title_norm: str, recent_titles: list[str]) -> bool:
    for other in recent_titles:
        matcher = SequenceMatcher(None, title_norm, other)
        if matcher.quick_ratio() >= NEAR_DUPLICATE_THRESHOLD and matcher.ratio() >= NEAR_DUPLICATE_THRESHOLD:
            return True
    return False


_robots_cache: dict[str, RobotFileParser] = {}


def allowed_by_robots(client: httpx.Client, url: str) -> bool:
    parts = urlparse(url)
    base = f"{parts.scheme}://{parts.netloc}"
    if base not in _robots_cache:
        parser = RobotFileParser()
        try:
            response = client.get(base + "/robots.txt")
            parser.parse(response.text.splitlines() if response.status_code == 200 else [])
        except httpx.HTTPError:
            parser.parse([])
        _robots_cache[base] = parser
    return _robots_cache[base].can_fetch(HEADERS["User-Agent"], url)


def fetch_feed(client: httpx.Client, feed_url: str) -> list:
    response = client.get(feed_url)
    response.raise_for_status()
    return feedparser.parse(response.content).entries


def fetch_article_text(client: httpx.Client, url: str) -> str | None:
    if not allowed_by_robots(client, url):
        return None
    try:
        time.sleep(DELAY_BETWEEN_REQUESTS)
        response = client.get(url)
        if response.status_code != 200:
            return None
        return trafilatura.extract(response.text, include_comments=False, include_tables=False,
                                   favor_precision=True)
    except httpx.HTTPError:
        return None


def load_existing() -> tuple[set[str], list[str]]:
    ids, recent_titles = set(), []
    if not DATA_FILE.exists():
        return ids, recent_titles
    border = datetime.now(timezone.utc) - NEAR_DUPLICATE_WINDOW
    with DATA_FILE.open(encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            record = json.loads(line)
            ids.add(record["id"])
            published = record.get("published_at")
            if published and datetime.fromisoformat(published) >= border:
                recent_titles.append(normalize_title(record["title"]))
    return ids, recent_titles


def collect() -> dict:
    DATA_FILE.parent.mkdir(parents=True, exist_ok=True)
    seen_ids, recent_titles = load_existing()
    stats = {"new": 0, "duplicate": 0, "near_duplicate": 0, "non_business": 0, "too_short": 0,
             "partial_text": 0, "source_errors": 0}

    with httpx.Client(headers=HEADERS, timeout=TIMEOUT, follow_redirects=True) as client, \
            DATA_FILE.open("a", encoding="utf-8") as out:
        for source, cfg in SOURCES.items():
            try:
                entries = fetch_feed(client, cfg["url"])
            except Exception as error:
                stats["source_errors"] += 1
                print(f"[{source}] источник недоступен: {error}")
                continue

            for entry in entries[:LIMIT_PER_SOURCE]:
                link = entry.get("link")
                title = clean_text(entry.get("title", ""))
                if not link or not title:
                    continue

                url = canonical_url(link)
                news_id = make_id(url)
                if news_id in seen_ids:
                    stats["duplicate"] += 1
                    continue

                categories = [tag.get("term", "") for tag in entry.get("tags", [])]
                if not is_business(title, categories):
                    stats["non_business"] += 1
                    continue

                title_norm = normalize_title(title)
                if is_near_duplicate(title_norm, recent_titles):
                    stats["near_duplicate"] += 1
                    continue

                summary = clean_text(entry.get("summary", ""))
                full_text = fetch_article_text(client, link)
                text = clean_text(full_text) if full_text else summary
                if len(text) < MIN_TEXT_LENGTH:
                    stats["too_short"] += 1
                    continue
                if not full_text:
                    stats["partial_text"] += 1

                published = parse_date(entry)
                record = {
                    "id": news_id,
                    "source": source,
                    "source_tier": cfg["tier"],
                    "url": url,
                    "title": title,
                    "summary": summary,
                    "text": text,
                    "categories": categories,
                    "published_at": published.isoformat() if published else None,
                    "collected_at": datetime.now(timezone.utc).isoformat(),
                    "text_partial": full_text is None,
                }
                out.write(json.dumps(record, ensure_ascii=False) + "\n")
                out.flush()
                seen_ids.add(news_id)
                recent_titles.append(title_norm)
                stats["new"] += 1

            print(f"[{source}] готово")

    print("Итог:", stats)
    return stats

def recent_materials(hours: int = 24) -> list[dict]:
    if not DATA_FILE.exists():
        return []
    border = datetime.now(timezone.utc) - timedelta(hours=hours)
    materials = []
    with DATA_FILE.open(encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            record = json.loads(line)
            if datetime.fromisoformat(record["collected_at"]) < border:
                continue
            materials.append({
                "externalId": record["id"],
                "source": record["source"],
                "sourceTier": record["source_tier"],
                "url": record["url"],
                "title": record["title"],
                "text": record["text"],
                "publishedAt": datetime.fromisoformat(record["published_at"] or record["collected_at"])
                .astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            })
    return materials

if __name__ == "__main__":
    collect()