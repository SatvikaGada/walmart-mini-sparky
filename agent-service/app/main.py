import asyncio
import logging
import time
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import config
from .graph import INTENT, build_graph, parse_budget_paise
from .llm import LLMClient, LLMError
from .tools import ToolContext

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("agent")

STATIC_DIR = Path(__file__).parent / "static"


class Session:
    def __init__(self):
        self.history: list = []
        self.cart_id = None
        self.lock = asyncio.Lock()


class ChatRequest(BaseModel):
    sessionId: str = Field(min_length=8, max_length=64)
    message: str = Field(min_length=1, max_length=1000)


@asynccontextmanager
async def lifespan(app: FastAPI):
    if not config.LLM_API_KEY and not config.LLM_FALLBACK_BASE_URL:
        log.warning("LLM_API_KEY is empty: chat calls will fail")
    async with httpx.AsyncClient() as llm_http, \
            httpx.AsyncClient(base_url=config.CORE_BASE_URL, timeout=10) as core:
        app.state.llm = LLMClient(llm_http)
        app.state.graph = build_graph(app.state.llm)
        app.state.core = core
        app.state.sessions = {}
        yield


app = FastAPI(title="Mini-Sparky agent", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok", "model": config.LLM_MODEL, "core": config.CORE_BASE_URL}


@app.post("/chat")
async def chat(req: ChatRequest):
    session = app.state.sessions.setdefault(req.sessionId, Session())
    async with session.lock:
        ctx = ToolContext(session_id=req.sessionId, core=app.state.core, cart_id=session.cart_id)
        user_msg = {"role": "user", "content": req.message}
        state = {"messages": session.history + [user_msg], "trace": [], "ctx": ctx,
                 "budget_paise": parse_budget_paise(req.message),
                 "intent_shopping": bool(INTENT.search(req.message)),
                 "repair_count": 0, "repair_pending": False, "llm_steps": 0,
                 "prompt_tokens": 0, "completion_tokens": 0}
        started = time.monotonic()
        try:
            out = await app.state.graph.ainvoke(state, config={"recursion_limit": 60})
        except LLMError as e:
            log.error("LLM failure: %s", e)
            raise HTTPException(status_code=503, detail=f"The language model is unavailable: {e}")

        session.cart_id = ctx.cart_id
        session.history = (session.history + [user_msg, {"role": "assistant", "content": out["reply"]}])[-12:]
        return {"reply": out["reply"], "cart": out.get("cart"), "toolTrace": out["trace"],
                "meta": {"model": config.LLM_MODEL, "llmCalls": out["llm_steps"],
                         "promptTokens": out["prompt_tokens"], "completionTokens": out["completion_tokens"],
                         "latencyMs": int((time.monotonic() - started) * 1000)}}


# must come last so /health and /chat win
app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")