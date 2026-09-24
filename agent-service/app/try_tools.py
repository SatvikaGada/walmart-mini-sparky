"""Dev script: run the tools against the running core. Run: python -m app.try_tools"""
import asyncio

import httpx

from . import config
from .tools import ToolContext, run_tool


async def main():
    async with httpx.AsyncClient(base_url=config.CORE_BASE_URL, timeout=10) as core:
        session = (await core.post("/api/session")).json()
        ctx = ToolContext(session_id=session["sessionId"], core=core)
        steps = [
            ("search_products", '{"query": "chips", "max_price_rupees": 100}'),
            ("create_cart", '{"budget_rupees": 300}'),
            ("add_to_cart", '{"sku": "SNK-001", "qty": 2}'),
            ("add_to_cart", '{"sku": "SNK-001", "qty": 9}'),
            ("add_to_cart", '{"sku": "snk 1", "qty": 1}'),
            ("checkout", "{}"),
            ("view_cart", ""),
        ]
        for name, args in steps:
            content, entry = await run_tool(name, args, ctx)
            print(f"{entry['status']:9} {name} {args}\n          -> {content[:260]}\n")


asyncio.run(main())