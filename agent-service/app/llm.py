"""Provider-agnostic chat client for any OpenAI-compatible /chat/completions endpoint."""
import asyncio
import logging
import time
from dataclasses import dataclass

import httpx

from . import config

log = logging.getLogger("llm")


class LLMError(Exception):
    """Raised when no configured provider could answer."""


@dataclass
class Provider:
    name: str
    base_url: str
    api_key: str
    model: str


@dataclass
class LLMResult:
    message: dict
    prompt_tokens: int = 0
    completion_tokens: int = 0
    latency_ms: int = 0
    provider: str = ""


def to_replay_message(msg: dict, step: int) -> dict:
    """Keep exactly what must be sent back to the provider, including Gemini's
    extra_content thought signatures, and make sure every tool call has a unique id."""
    out = {"role": "assistant", "content": msg.get("content") or ""}
    calls = msg.get("tool_calls") or []
    if calls:
        seen = set()
        fixed = []
        for i, tc in enumerate(calls):
            tc = dict(tc)
            if not tc.get("id") or tc["id"] in seen:
                tc["id"] = f"call_{step}_{i}"
            seen.add(tc["id"])
            tc.setdefault("type", "function")
            fixed.append(tc)
        out["tool_calls"] = fixed
    if msg.get("extra_content"):
        out["extra_content"] = msg["extra_content"]
    return out


def _retry_after(resp: httpx.Response):
    try:
        return float(resp.headers.get("retry-after", ""))
    except ValueError:
        return None


class LLMClient:
    MAX_ATTEMPTS = 4

    def __init__(self, http: httpx.AsyncClient):
        self.http = http
        self.providers = [Provider("primary", config.LLM_BASE_URL, config.LLM_API_KEY, config.LLM_MODEL)]
        if config.LLM_FALLBACK_BASE_URL and config.LLM_FALLBACK_MODEL:
            self.providers.append(Provider("fallback", config.LLM_FALLBACK_BASE_URL,
                                           config.LLM_FALLBACK_API_KEY, config.LLM_FALLBACK_MODEL))
        self._lock = asyncio.Lock()
        self._last_call = 0.0

    async def _throttle(self):
        if config.LLM_MIN_INTERVAL_SECONDS <= 0:
            return
        async with self._lock:
            wait = self._last_call + config.LLM_MIN_INTERVAL_SECONDS - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            self._last_call = time.monotonic()

    async def chat(self, messages: list, tools: list) -> LLMResult:
        last_error = "no provider configured"
        for provider in self.providers:
            try:
                return await self._call(provider, messages, tools)
            except LLMError as e:
                last_error = f"{provider.name}: {e}"
                log.warning("LLM provider failed: %s", last_error)
        raise LLMError(last_error)

    async def _call(self, p: Provider, messages: list, tools: list) -> LLMResult:
        body = {"model": p.model, "messages": messages, "tools": tools, "tool_choice": "auto"}
        if config.LLM_TEMPERATURE is not None:
            body["temperature"] = float(config.LLM_TEMPERATURE)
        if config.LLM_REASONING_EFFORT and p.name == "primary":
            body["reasoning_effort"] = config.LLM_REASONING_EFFORT
        headers = {"Authorization": f"Bearer {p.api_key}"}
        url = p.base_url.rstrip("/") + "/chat/completions"

        delay = 2.0
        for attempt in range(1, self.MAX_ATTEMPTS + 1):
            await self._throttle()
            started = time.monotonic()
            try:
                resp = await self.http.post(url, json=body, headers=headers, timeout=60)
            except httpx.HTTPError as e:
                if attempt == self.MAX_ATTEMPTS:
                    raise LLMError(f"network error: {type(e).__name__}") from e
                await asyncio.sleep(delay)
                delay *= 2
                continue

            if resp.status_code == 429 or resp.status_code >= 500:
                if attempt == self.MAX_ATTEMPTS:
                    raise LLMError(f"HTTP {resp.status_code}: {resp.text[:200]}")
                wait = min(_retry_after(resp) or delay, 30)
                log.warning("%s HTTP %s, retry %s in %.0fs", p.name, resp.status_code, attempt, wait)
                await asyncio.sleep(wait)
                delay *= 2
                continue

            if resp.status_code >= 400:
                raise LLMError(f"HTTP {resp.status_code}: {resp.text[:300]}")

            try:
                data = resp.json()
                message = data["choices"][0]["message"]
            except (ValueError, KeyError, IndexError, TypeError) as e:
                raise LLMError(f"unexpected response: {resp.text[:200]}") from e

            usage = data.get("usage") or {}
            latency = int((time.monotonic() - started) * 1000)
            log.info("%s %s ok in %d ms (prompt=%s completion=%s)", p.name, p.model, latency,
                     usage.get("prompt_tokens"), usage.get("completion_tokens"))
            return LLMResult(message, usage.get("prompt_tokens", 0) or 0,
                             usage.get("completion_tokens", 0) or 0, latency, p.name)
        raise LLMError("retries exhausted")