from app.graph import build_graph
from app.llm import LLMResult
from app.tools import ToolContext


class FakeLLM:
    def __init__(self, script):
        self.script = list(script)

    async def chat(self, messages, tools):
        msg = self.script.pop(0)
        return LLMResult(msg, prompt_tokens=10, completion_tokens=10, latency_ms=5, provider="fake")


def tool_call(call_id, name, arguments):
    return {"content": None,
            "tool_calls": [{"id": call_id, "type": "function",
                            "function": {"name": name, "arguments": arguments}}]}


def final(text):
    return {"content": text}


def base_state(ctx, budget_paise, intent_shopping):
    return {"messages": [{"role": "user", "content": "chips for a small party under Rs 60"}],
            "trace": [], "ctx": ctx, "budget_paise": budget_paise, "intent_shopping": intent_shopping,
            "repair_count": 0, "repair_pending": False, "llm_steps": 0,
            "prompt_tokens": 0, "completion_tokens": 0}


async def test_verify_repairs_an_empty_cart_then_finishes(fake_core):
    script = [
        tool_call("c1", "create_cart", '{"budget_rupees": 60}'),
        tool_call("c2", "search_products", '{"query": "chips"}'),
        final("I could not find anything."),
        tool_call("c3", "add_to_cart", '{"sku": "SNK-001", "qty": 1}'),
        final("Here is your cart."),
    ]
    graph = build_graph(FakeLLM(script))
    ctx = ToolContext(session_id="s1", core=fake_core)
    out = await graph.ainvoke(base_state(ctx, 6000, True), config={"recursion_limit": 60})

    assert "Confirm & Pay" in out["reply"]
    assert out["cart"]["items"]
    assert out["cart"]["totalPaise"] <= 6000
    assert out["repair_count"] == 1


async def test_hits_step_limit_gracefully(fake_core, monkeypatch):
    import app.config as config
    monkeypatch.setattr(config, "MAX_LLM_STEPS", 2)
    script = [tool_call(f"c{i}", "view_cart", "{}") for i in range(10)]
    graph = build_graph(FakeLLM(script))
    ctx = ToolContext(session_id="s1", core=fake_core)
    out = await graph.ainvoke(base_state(ctx, None, False), config={"recursion_limit": 60})

    assert out["llm_steps"] == 2
    assert isinstance(out["reply"], str)