import asyncio
import json
from types import SimpleNamespace
import pytest
from app.generation import GenerationError
from app.tutor import TutorRequest
from app.tutor_agent import coach, retrieve


class Model:
    settings = SimpleNamespace(model="test")
    def __init__(self, *actions):
        self.actions = iter(actions)
        self.calls = []
    async def _complete(self, payload, key):
        self.calls.append(payload)
        return json.dumps(next(self.actions), ensure_ascii=False)


def action(tool="", query="", ref="", citations=None):
    return {"action": "LOOKUP" if tool else "FINISH", "tool": tool, "query": query, "ref": ref,
            "answer": "" if tool else "可以对比局部与全局的统计范围。", "teaching": "COMPARE", "citations": citations or []}


def request():
    return TutorRequest(message="怎么区分？", toolToken="a"*64, context=json.dumps({
        "evidence": [{"ref": "PRACTICE:attempt"}], "feedbackMemory": [{"feedback": "INCORRECT", "detail": "不是没看题，是概念混淆"}]}))


def test_direct_answer_costs_one_call_and_secret_never_enters_prompt():
    model = Model(action(citations=["PRACTICE:attempt"]))
    result = asyncio.run(coach(model, request(), "synthetic"))
    assert result["trace"] == [] and len(model.calls) == 1
    prompt = json.dumps(model.calls, ensure_ascii=False)
    assert "a"*64 not in prompt and "synthetic" not in prompt
    assert "不是没看题" in json.loads(model.calls[0]["messages"][1]["content"])["feedbackMemory"][0]["detail"]


def test_real_lookup_results_feed_next_node_and_prefix_stays_unchanged():
    calls = []
    async def lookup(token, item):
        calls.append(item)
        assert token == "a"*64
        return {"items": [{"ref": "SOURCE:7", "content": "局部直方图使用邻域统计"}]}
    model = Model(action("search_material", "局部"), action(citations=["SOURCE:7"]))
    result = asyncio.run(coach(model, request(), "synthetic", lookup))
    assert len(calls) == 1 and result["trace"] == [{"tool": "search_material", "count": 1}]
    assert model.calls[1]["messages"][:3] == model.calls[0]["messages"]
    assert "局部直方图使用邻域统计" in model.calls[1]["messages"][-1]["content"]


@pytest.mark.parametrize("actions", [
    [action(citations=["SOURCE:invented"])],
    [action("search_material", "局部"), action("search_material", "局部")],
    [action("attempt_detail", ref="PRACTICE:other-user")],
    [action("search_material", "局部"), action("search_notes", "局部"), action("search_material", "全局")],
])
def test_rejects_invented_sources_repeats_unauthorized_ids_and_excess_budget(actions):
    async def lookup(*args): return {"items": []}
    model = Model(*actions)
    with pytest.raises(GenerationError, match="MODEL_OUTPUT_INVALID"):
        asyncio.run(coach(model, request(), "synthetic", lookup))
    assert len(model.calls) <= 3


def test_empty_search_is_reported_as_empty_and_then_finishes():
    async def lookup(*args): return {"items": []}
    model = Model(action("search_notes", "局部"), action())
    assert asyncio.run(coach(model, request(), "synthetic", lookup))["trace"][0]["count"] == 0


def test_retrieval_configuration_cannot_redirect_private_context(monkeypatch):
    monkeypatch.setenv("LEARNLOOP_TUTOR_RETRIEVAL_URL", "https://example.org/internal/tutor/retrieve")
    with pytest.raises(GenerationError, match="MODEL_NOT_CONFIGURED"):
        asyncio.run(retrieve("a"*64, action("search_notes", "局部")))
