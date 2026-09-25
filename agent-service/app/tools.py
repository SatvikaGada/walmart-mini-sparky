"""The tools the agent may use. There is deliberately NO checkout tool."""
import json
from dataclasses import dataclass
from typing import Annotated, Any, Optional
import logging

import httpx
from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, StringConstraints, ValidationError

from . import config, sanitize

POLICY_CODES = {"QTY_LIMIT", "LINE_LIMIT", "BUDGET_EXCEEDED", "CART_NOT_OPEN", "CART_EXPIRED",
                "OUT_OF_STOCK", "RATE_LIMITED", "FORBIDDEN", "EMPTY_CART"}

log = logging.getLogger("tools")


def _norm_sku(v):
    return v.strip().upper() if isinstance(v, str) else v


Sku = Annotated[str, BeforeValidator(_norm_sku), StringConstraints(pattern=r"^[A-Z0-9-]{3,20}$")]


class _Args(BaseModel):
    model_config = ConfigDict(extra="forbid")   # unknown arguments are rejected


class SearchArgs(_Args):
    query: Optional[str] = Field(default=None, max_length=60)
    category: Optional[str] = Field(default=None, max_length=20)
    tag: Optional[str] = Field(default=None, max_length=20)
    max_price_rupees: Optional[int] = Field(default=None, ge=1, le=100000)
    limit: Optional[int] = None


class SkuArgs(_Args):
    sku: Sku


class CreateCartArgs(_Args):
    budget_rupees: Optional[int] = Field(default=None, ge=1, le=100000)


class AddArgs(_Args):
    sku: Sku
    qty: int = Field(ge=1, le=20)


class NoArgs(_Args):
    pass


def _spec(name, description, properties=None, required=None):
    fn = {"name": name, "description": description}
    if properties:  # some providers reject an empty parameters object, so omit it
        fn["parameters"] = {"type": "object", "properties": properties, "required": required or []}
    return {"type": "function", "function": fn}


_SKU = {"type": "string", "description": "product sku, e.g. SNK-001"}

TOOL_SPECS = [
    _spec("search_products", "Search the catalog. Returns sku, name, price in rupees, stock.",
          {"query": {"type": "string", "description": "product word, e.g. chips"},
           "category": {"type": "string", "description": "grocery, snacks, beverages, party or home"},
           "tag": {"type": "string", "description": "e.g. vegetarian, bbq, party"},
           "max_price_rupees": {"type": "integer"},
           "limit": {"type": "integer", "description": "max 10"}}),
    _spec("get_product_details", "One product with description and reviews.", {"sku": _SKU}, ["sku"]),
    _spec("check_stock", "Available stock for a sku.", {"sku": _SKU}, ["sku"]),
    _spec("create_cart", "Create the shopping cart (reuses the current one). Call this first.",
          {"budget_rupees": {"type": "integer", "description": "the user's budget in rupees"}}),
    _spec("add_to_cart", "Add units of a sku to the cart (reserves stock).",
          {"sku": _SKU, "qty": {"type": "integer", "description": "1 to 20"}}, ["sku", "qty"]),
    _spec("remove_from_cart", "Remove a sku from the cart.", {"sku": _SKU}, ["sku"]),
    _spec("view_cart", "Cart items, total and remaining budget."),
]


@dataclass
class ToolContext:
    session_id: str
    core: httpx.AsyncClient
    cart_id: Optional[str] = None

    async def call(self, method: str, path: str, **kwargs) -> httpx.Response:
        headers = {"X-Agent-Key": config.AGENT_API_KEY, "X-Session-Id": self.session_id}
        return await self.core.request(method, path, headers=headers, **kwargs)


def _rs(paise: int):
    value = paise / 100
    return int(value) if value == int(value) else round(value, 2)


def _err(resp: httpx.Response) -> dict:
    try:
        body = resp.json()
        if isinstance(body, dict):
            return {"error": body.get("code", f"HTTP_{resp.status_code}"),
                    "message": str(body.get("message", ""))[:160],
                    "details": body.get("details", {})}
    except ValueError:
        pass
    return {"error": f"HTTP_{resp.status_code}", "message": resp.text[:160]}


def compact_cart(cart: dict) -> dict:
    return {"status": cart["status"],
            "items": [{"sku": i["sku"], "name": sanitize.clean(i["name"], 40), "qty": i["qty"],
                       "line_rs": _rs(i["linePaise"])} for i in cart["items"]],
            "total_rs": _rs(cart["totalPaise"]), "remaining_rs": _rs(cart["remainingBudgetPaise"])}


NO_CART = {"error": "NO_CART", "message": "There is no cart yet. Call create_cart first."}


async def _search(ctx: ToolContext, a: SearchArgs) -> dict:
    params: dict[str, Any] = {"limit": min(max(a.limit or 10, 1), 10)}
    if a.query:
        params["q"] = a.query
    if a.category:
        params["category"] = a.category
    if a.tag:
        params["tag"] = a.tag
    if a.max_price_rupees:
        params["maxPricePaise"] = a.max_price_rupees * 100
    r = await ctx.call("GET", "/api/products/search", params=params)
    if r.status_code != 200:
        return _err(r)
    results = [{"sku": p["sku"], "name": sanitize.clean(p["name"], 50), "price_rs": _rs(p["pricePaise"]),
                "in_stock": p["available"], "cat": p["category"], "tags": p["tags"]} for p in r.json()]
    out: dict[str, Any] = {"results": results}
    if not results:
        out["hint"] = "No matches. Try a simpler word or use the category/tag filters."
    return out


async def _details(ctx: ToolContext, a: SkuArgs) -> dict:
    r = await ctx.call("GET", f"/api/products/{a.sku}")
    if r.status_code != 200:
        return _err(r)
    p = r.json()
    flags = set(sanitize.flag(sanitize.clean(p["description"], 2000)))
    for rv in p["reviews"][:5]:
        flags.update(sanitize.flag(sanitize.clean(rv["body"], 2000)))
    result = {"sku": p["sku"], "name": sanitize.clean(p["name"], 50), "price_rs": _rs(p["pricePaise"]),
              "in_stock": p["available"], "max_qty": p["maxQtyPerOrder"],
              "description": sanitize.wrap(p["description"], 300),
              "reviews": [{"author": sanitize.clean(rv["author"], 30), "text": sanitize.wrap(rv["body"], 200)}
                          for rv in p["reviews"][:5]]}
    if flags:
        result["_flags"] = sorted(flags)
    return result


async def _stock(ctx: ToolContext, a: SkuArgs) -> dict:
    r = await ctx.call("GET", f"/api/stock/{a.sku}")
    if r.status_code != 200:
        return _err(r)
    return {"sku": a.sku, "available": r.json()["available"]}


async def _create_cart(ctx: ToolContext, a: CreateCartArgs) -> dict:
    if ctx.cart_id:
        r = await ctx.call("GET", f"/api/carts/{ctx.cart_id}")
        if r.status_code == 200 and r.json()["status"] == "OPEN":
            return {"cart": "existing", **compact_cart(r.json())}
        ctx.cart_id = None                      # expired or gone: start a new one
    budget = a.budget_rupees * 100 if a.budget_rupees else None
    r = await ctx.call("POST", "/api/carts", json={"budgetPaise": budget})
    if r.status_code != 200:
        return _err(r)
    ctx.cart_id = r.json()["cartId"]
    return {"cart": "created", "budget_rs": a.budget_rupees}


async def _add(ctx: ToolContext, a: AddArgs) -> dict:
    if not ctx.cart_id:
        return NO_CART
    r = await ctx.call("POST", f"/api/carts/{ctx.cart_id}/items", json={"sku": a.sku, "qty": a.qty})
    if r.status_code == 200:
        return compact_cart(r.json())
    err = _err(r)
    if err["error"] in ("CART_EXPIRED", "CART_NOT_OPEN"):
        ctx.cart_id = None
        err["message"] += " Call create_cart to start a new cart."
    return err


async def _remove(ctx: ToolContext, a: SkuArgs) -> dict:
    if not ctx.cart_id:
        return NO_CART
    r = await ctx.call("DELETE", f"/api/carts/{ctx.cart_id}/items/{a.sku}")
    return compact_cart(r.json()) if r.status_code == 200 else _err(r)


async def _view(ctx: ToolContext, a: NoArgs) -> dict:
    if not ctx.cart_id:
        return NO_CART
    r = await ctx.call("GET", f"/api/carts/{ctx.cart_id}")
    return compact_cart(r.json()) if r.status_code == 200 else _err(r)


TOOLS = {
    "search_products": (SearchArgs, _search),
    "get_product_details": (SkuArgs, _details),
    "check_stock": (SkuArgs, _stock),
    "create_cart": (CreateCartArgs, _create_cart),
    "add_to_cart": (AddArgs, _add),
    "remove_from_cart": (SkuArgs, _remove),
    "view_cart": (NoArgs, _view),
}


def _status(result: dict) -> str:
    if "error" not in result:
        return "ALLOWED"
    if result["error"] in POLICY_CODES:
        return "BLOCKED"        # the backend refused: this is what the safety story is about
    if result["error"] in ("INVALID_ARGS", "UNKNOWN_TOOL", "NO_CART"):
        return "REJECTED"       # refused inside the agent service, never reached the backend
    return "ERROR"


async def run_tool(name: str, raw_args: Any, ctx: ToolContext) -> tuple[str, dict]:
    """Returns (JSON text for the LLM, trace entry for the UI and the eval)."""
    entry = {"name": name, "args": {}, "status": "", "result": ""}
    spec = TOOLS.get(name)
    if spec is None:
        result = {"error": "UNKNOWN_TOOL", "message": f"There is no tool named '{name}'."}
    else:
        model, fn = spec
        try:
            args = json.loads(raw_args) if isinstance(raw_args, str) and raw_args.strip() else (raw_args or {})
            entry["args"] = args if isinstance(args, dict) else str(args)[:200]
            parsed = model.model_validate(args)
        except (ValueError, ValidationError) as e:
            result = {"error": "INVALID_ARGS", "message": str(e)[:200]}
        else:
            try:
                result = await fn(ctx, parsed)
            except httpx.HTTPError as e:
                result = {"error": "CORE_UNAVAILABLE", "message": type(e).__name__}
    flags = result.pop("_flags", None) if isinstance(result, dict) else None
    content = json.dumps(result, separators=(",", ":"), ensure_ascii=False)
    entry["status"] = _status(result)
    entry["result"] = content[:240]
    if flags:
        entry["flags"] = flags
        log.warning("possible prompt injection in tool result: %s %s", name, flags)
    return content, entry