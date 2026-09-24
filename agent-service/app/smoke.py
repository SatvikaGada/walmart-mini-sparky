"""Checks that the LLM provider does tool calling. Run: python -m app.smoke"""
import asyncio
import json

import httpx

from .llm import LLMClient, to_replay_message
from .prompts import SYSTEM_PROMPT
from .tools import TOOL_SPECS


async def main():
    async with httpx.AsyncClient() as http:
        llm = LLMClient(http)
        msgs = [{"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": "How many units of SNK-001 are in stock?"}]
        first = await llm.chat(msgs, TOOL_SPECS)
        print("provider:", first.provider, "| latency ms:", first.latency_ms)
        calls = first.message.get("tool_calls") or []
        if not calls:
            print("NO TOOL CALL. Model said:", first.message.get("content"))
            return
        print("tool call:", json.dumps(calls[0].get("function")))
        print("has thought signature:", "extra_content" in calls[0])

        replay = to_replay_message(first.message, 0)
        msgs.append(replay)
        for tc in replay["tool_calls"]:
            msgs.append({"role": "tool", "tool_call_id": tc["id"], "name": tc["function"]["name"],
                         "content": '{"sku":"SNK-001","available":50}'})
        second = await llm.chat(msgs, TOOL_SPECS)
        print("final answer:", second.message.get("content"))


asyncio.run(main())