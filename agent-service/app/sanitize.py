"""Untrusted-content handling: strip tricks, cap length, wrap in data tags."""
import re

OPEN_TAG = "<untrusted_product_data>"
CLOSE_TAG = "</untrusted_product_data>"

_INVISIBLE = re.compile(r"[\u200b-\u200f\u202a-\u202e\u2060-\u2064\ufeff]")
_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_TAGS = re.compile(r"</?\s*untrusted_product_data\s*>", re.IGNORECASE)


def clean(text, limit: int = 300) -> str:
    if not text:
        return ""
    text = _INVISIBLE.sub("", str(text))   # zero-width / bidi tricks
    text = _CONTROL.sub("", text)
    text = _TAGS.sub("", text)             # nobody can close our wrapper early
    text = " ".join(text.split())
    return text[:limit]


def wrap(text, limit: int = 300) -> str:
    return f"{OPEN_TAG}{clean(text, limit)}{CLOSE_TAG}"