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

_INJECTION_PATTERNS = {
    "ignore_instructions": re.compile(
        r"ignore (?:the |earlier |previous |prior |your )?(?:instructions|rules)", re.I),
    "role_override": re.compile(r"\byou are now\b|\bact as\b|\bsystem notice\b|\bsystem:\b", re.I),
    "authority_claim": re.compile(r"\b(store manager|administrator|authorized by)\b", re.I),
    "budget_override": re.compile(
        r"budget (?:limits? )?(?:are|is) disabled|no budget|unlimited budget|"
        r"ignore.{0,20}budget|override.{0,20}budget", re.I),
    "fake_tool_call": re.compile(r'"name"\s*:\s*"?(checkout|call_checkout)"?|call checkout', re.I),
    "reveal_prompt": re.compile(r"(reveal|print|show).{0,20}(system prompt|instructions|canary)", re.I),
}


def flag(text: str) -> list[str]:
    """A signal only. It can be evaded by paraphrase or another language, and it must
    never be relied on for safety — the backend's enforcement is the real guarantee."""
    if not text:
        return []
    return sorted(label for label, pattern in _INJECTION_PATTERNS.items() if pattern.search(text))