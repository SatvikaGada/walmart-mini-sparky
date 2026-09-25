from app.tools import ToolContext, run_tool


async def test_search_finds_chips(fake_core):
    ctx = ToolContext(session_id="s1", core=fake_core)
    content, entry = await run_tool("search_products", '{"query": "chips"}', ctx)
    assert entry["status"] == "ALLOWED"
    assert "SNK-001" in content


async def test_add_over_qty_limit_is_blocked(fake_core):
    ctx = ToolContext(session_id="s1", core=fake_core)
    await run_tool("create_cart", "{}", ctx)
    content, entry = await run_tool("add_to_cart", '{"sku": "SNK-001", "qty": 11}', ctx)
    assert entry["status"] == "BLOCKED"
    assert "QTY_LIMIT" in content


async def test_invalid_sku_never_reaches_core(fake_core):
    ctx = ToolContext(session_id="s1", core=fake_core)
    content, entry = await run_tool("add_to_cart", '{"sku": "not a sku!", "qty": 1}', ctx)
    assert entry["status"] == "REJECTED"
    assert "INVALID_ARGS" in content


async def test_unknown_tool_is_rejected(fake_core):
    ctx = ToolContext(session_id="s1", core=fake_core)
    content, entry = await run_tool("checkout", "{}", ctx)
    assert entry["status"] == "REJECTED"
    assert "UNKNOWN_TOOL" in content


async def test_extra_argument_is_rejected(fake_core):
    ctx = ToolContext(session_id="s1", core=fake_core)
    content, entry = await run_tool(
        "add_to_cart", '{"sku":"SNK-001","qty":1,"skip_payment_check":true}', ctx)
    assert entry["status"] == "REJECTED"


async def test_injected_review_is_flagged_and_wrapped_not_executed(fake_core):
    ctx = ToolContext(session_id="s1", core=fake_core)
    content, entry = await run_tool("get_product_details", '{"sku": "SNK-001"}', ctx)
    assert entry["status"] == "ALLOWED"
    assert entry.get("flags")
    assert "<untrusted_product_data>" in content