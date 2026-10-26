"""Локальные RSS и статьи для настоящего коллектора, без внешних источников."""
import os
from datetime import datetime, timezone
from email.utils import format_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread

from app import news
from app.main import app

PREFIX = os.environ["QA_NEWS_PREFIX"]
ARTICLE = "Central bank financing policy changes corporate borrowing costs. " * 12


class SourceHandler(BaseHTTPRequestHandler):
    """Отдаёт тестовые источники по HTTP и отдельный недоступный RSS."""

    def do_GET(self):
        """Формирует RSS, robots.txt или настоящую HTML-статью."""
        if self.path == "/broken.rss":
            self.send_error(503)
            return
        if self.path == "/robots.txt":
            body = b"User-agent: *\nAllow: /\n"
            content_type = "text/plain"
        elif self.path == "/feed.rss":
            date = format_datetime(datetime.now(timezone.utc))
            items = [
                (f"{PREFIX} Central bank financing update", "/article"),
                (f"{PREFIX} Central bank financing update", "/article?utm_source=repeat"),
                (f"{PREFIX} футбол чемпионат", "/sports"),
            ]
            body = ("<rss version='2.0'><channel><title>QA</title>" + "".join(
                f"<item><title>{title}</title><link>http://127.0.0.1:8099{path}</link>"
                f"<pubDate>{date}</pubDate><description>{ARTICLE}</description></item>"
                for title, path in items
            ) + "</channel></rss>").encode()
            content_type = "application/rss+xml"
        else:
            body = (f"<html><head><title>Bank financing</title></head><body><article>"
                    f"<h1>Bank financing update</h1><p>{ARTICLE}</p></article></body></html>").encode()
            content_type = "text/html"
        self.send_response(200)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        """Не засоряет логи служебными запросами локального источника."""


server = ThreadingHTTPServer(("127.0.0.1", 8099), SourceHandler)
Thread(target=server.serve_forever, daemon=True).start()
news.SOURCES = {
    "qa-local": {"url": "http://127.0.0.1:8099/feed.rss", "tier": 1},
    "qa-failed": {"url": "http://127.0.0.1:8099/broken.rss", "tier": 2},
}
news.DATA_FILE = Path("/tmp/qa-news/news.jsonl")
