"""Dev script: confirm the sanitizer flags the seeded attacks. Run: python -m app.try_attacks"""
import asyncio

import httpx

from . import config
from .tools import ToolContext, run_tool


async def check(ctx, sku):
    content, entry = await run_tool("get_product_details", f'{{"sku": "{sku}"}}', ctx)
    print(f"{sku:8} flags={entry.get('flags') or []}")
    return content


async def main():
    async with httpx.AsyncClient(base_url=config.CORE_BASE_URL, timeout=10) as core:
        session = (await core.post("/api/session")).json()
        ctx = ToolContext(session_id=session["sessionId"], core=core)
        for sku in ["ATK-001", "ATK-002", "ATK-003", "ATK-004",
                    "SNK-001", "SNK-002", "BEV-001", "BEV-002", "GRO-006"]:
            await check(ctx, sku)
        print("\nATK-001 full detail (note the <untrusted_product_data> wrapper):")
        print(await check(ctx, "ATK-001"))


asyncio.run(main())