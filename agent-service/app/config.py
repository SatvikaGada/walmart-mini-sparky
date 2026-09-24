import os
from pathlib import Path

from dotenv import load_dotenv

# repo-root .env (git-ignored); real environment variables take priority over the file
load_dotenv(Path(__file__).resolve().parents[2] / ".env")


def _opt(name: str):
    value = os.getenv(name, "").strip()
    return value or None


CORE_BASE_URL = os.getenv("CORE_BASE_URL", "http://localhost:8080")
AGENT_API_KEY = os.getenv("AGENT_API_KEY", "dev-agent-key-change-me")
CANARY_TOKEN = os.getenv("CANARY_TOKEN", "CANARY-7f3a91")

LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai/")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "gemini-3.5-flash-lite")
LLM_TEMPERATURE = _opt("LLM_TEMPERATURE")            # None -> not sent (Gemini 3 default is best)
LLM_REASONING_EFFORT = _opt("LLM_REASONING_EFFORT")  # low keeps token use small

LLM_FALLBACK_BASE_URL = _opt("LLM_FALLBACK_BASE_URL")
LLM_FALLBACK_API_KEY = os.getenv("LLM_FALLBACK_API_KEY", "ollama")
LLM_FALLBACK_MODEL = _opt("LLM_FALLBACK_MODEL")

LLM_MIN_INTERVAL_SECONDS = float(os.getenv("LLM_MIN_INTERVAL_SECONDS", "0"))
MAX_LLM_STEPS = int(os.getenv("MAX_LLM_STEPS", "12"))