"""LangGraph agent: agent -> tools -> agent ... -> verify -> (agent | respond)."""
import operator
import re
from typing import Annotated, Any, Optional, TypedDict

import httpx
from langgraph.graph import END, START, StateGraph

from . import config
from .llm import LLMClient, to_replay_message
from .prompts import SYSTEM_PROMPT
from .tools import TOOL_SPECS, run_tool

MAX_REPAIRS = 2
MAX_TOOL_CALLS_PER_STEP = 6

_BUDGET = re.compile(
    r"(?:under|below|within|budget(?:\s+of)?)\s*(?:rs\.?|₹|inr)?\s*([\d,]+)|(?:rs\.?|₹|inr)\s*([\d,]+)",
    re.IGNORECASE)
INTENT = re.compile(r"\b(plan|buy|need|get|cart|party|cookout|snacks?|supplies|order|shopping|add|budget|under)\b",
                    re.IGNORECASE)


def parse_budget_paise(text: str) -> Optional[int]:
    m = _BUDGET.search(text)
    if not m:
        return None
    digits = (m.group(1) or m.group(2) or "").replace(",", "")
    return int(digits) * 100 if digits.isdigit() else None


def rs(paise: int) -> str:
    value = paise / 100
    return f"{value:,.0f}" if value == int(value) else f"{value:,.2f}"


class AgentState(TypedDict, total=False):
    messages: Annotated[list, operator.add]
    trace: Annotated[list, operator.add]
    ctx: Any
    budget_paise: Optional[int]
    intent_shopping: bool
    repair_count: int
    repair_pending: bool
    llm_steps: int
    prompt_tokens: int
    completion_tokens: int
    final_text: str
    cart: Optional[dict]
    reply: str


def build_graph(llm: LLMClient):

    async def agent(state: AgentState):
        messages = [{"role": "system", "content": SYSTEM_PROMPT}] + state["messages"]
        result = await llm.chat(messages, TOOL_SPECS)
        return {"messages": [to_replay_message(result.message, state["llm_steps"])],
                "llm_steps": state["llm_steps"] + 1,
                "prompt_tokens": state["prompt_tokens"] + result.prompt_tokens,
                "completion_tokens": state["completion_tokens"] + result.completion_tokens}

    def after_agent(state: AgentState):
        wants_tools = bool(state["messages"][-1].get("tool_calls"))
        return "tools" if wants_tools and state["llm_steps"] < config.MAX_LLM_STEPS else "verify"

    async def tools(state: AgentState):
        ctx = state["ctx"]
        out, entries = [], []
        for i, tc in enumerate(state["messages"][-1]["tool_calls"]):
            fn = tc.get("function", {})
            name = fn.get("name", "")
            if i >= MAX_TOOL_CALLS_PER_STEP:     # every call still needs an answer
                content = '{"error":"TOO_MANY_CALLS","message":"Max 6 tool calls per step."}'
                entry = {"name": name, "args": {}, "status": "REJECTED", "result": content}
            else:
                content, entry = await run_tool(name, fn.get("arguments", ""), ctx)
            out.append({"role": "tool", "tool_call_id": tc["id"], "name": name, "content": content})
            entries.append(entry)
        return {"messages": out, "trace": entries}

    async def verify(state: AgentState):
        """Deterministic checks. No LLM here."""
        ctx = state["ctx"]
        last = state["messages"][-1]
        final_text = "" if last.get("tool_calls") else (last.get("content") or "").strip()

        cart = None
        if ctx.cart_id:
            try:
                r = await ctx.call("GET", f"/api/carts/{ctx.cart_id}")
                cart = r.json() if r.status_code == 200 else None
            except httpx.HTTPError:
                cart = None
        update: dict = {"final_text": final_text, "cart": cart, "repair_pending": False}

        if state["llm_steps"] >= config.MAX_LLM_STEPS or state["repair_count"] >= MAX_REPAIRS:
            return update

        feedback = None
        budget = state.get("budget_paise")
        empty = not cart or not cart["items"]
        tried_add = any(t["name"] == "add_to_cart" for t in state["trace"])
        if cart and budget and cart["totalPaise"] > budget:
            feedback = (f"The cart total Rs {rs(cart['totalPaise'])} is above the user's budget of "
                        f"Rs {rs(budget)}. Remove or swap items until it fits, then answer.")
        elif empty and state["intent_shopping"] and not tried_add and state["repair_count"] == 0:
            feedback = ("The cart is empty. If suitable items exist within the budget, add them with "
                        "add_to_cart; otherwise explain clearly why it cannot be done.")
        if feedback:
            update.update(repair_pending=True, repair_count=state["repair_count"] + 1,
                          messages=[{"role": "user", "content": "[system check] " + feedback}])
        return update

    def after_verify(state: AgentState):
        return "agent" if state.get("repair_pending") else "respond"

    async def respond(state: AgentState):
        cart = state.get("cart")
        has_items = bool(cart and cart["items"])
        text = state.get("final_text") or (
            "I hit my step limit before finishing. Here is what is in your cart so far." if has_items
            else "I couldn't complete that request.")
        if has_items:
            text += (f"\n\nCart total: Rs {rs(cart['totalPaise'])} "
                     f"(remaining budget: Rs {rs(cart['remainingBudgetPaise'])}). "
                     "Press Confirm & Pay to place the order. I can't do that myself.")
        return {"reply": text}

    g = StateGraph(AgentState)
    g.add_node("agent", agent)
    g.add_node("tools", tools)
    g.add_node("verify", verify)
    g.add_node("respond", respond)
    g.add_edge(START, "agent")
    g.add_conditional_edges("agent", after_agent, {"tools": "tools", "verify": "verify"})
    g.add_edge("tools", "agent")
    g.add_conditional_edges("verify", after_verify, {"agent": "agent", "respond": "respond"})
    g.add_edge("respond", END)
    return g.compile()