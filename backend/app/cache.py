"""A tiny TTL cache for resolved yt-dlp metadata.

Why this exists: /api/download must not trust a client-supplied format id, so it
re-derives the download plan from the extractor. Without a cache that means a
second full extraction per download, which blows the "< 2s download start"
target in PRD section 10. Entries are metadata only — no media bytes.

Deliberately not a dependency: this is ~40 lines and single-event-loop safe, so
cachetools would only add surface area.
"""

from __future__ import annotations

import time
from collections import OrderedDict
from typing import Any


class TTLCache:
    def __init__(self, maxsize: int, ttl: float) -> None:
        self._maxsize = maxsize
        self._ttl = ttl
        self._data: OrderedDict[str, tuple[float, Any]] = OrderedDict()

    def get(self, key: str) -> Any | None:
        entry = self._data.get(key)
        if entry is None:
            return None
        expires_at, value = entry
        if expires_at <= time.monotonic():
            del self._data[key]
            return None
        self._data.move_to_end(key)
        return value

    def set(self, key: str, value: Any) -> None:
        self._data[key] = (time.monotonic() + self._ttl, value)
        self._data.move_to_end(key)
        self._evict()

    def clear(self) -> None:
        self._data.clear()

    def _evict(self) -> None:
        now = time.monotonic()
        for key in [k for k, (exp, _) in self._data.items() if exp <= now]:
            del self._data[key]
        while len(self._data) > self._maxsize:
            self._data.popitem(last=False)
