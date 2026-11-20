import asyncio
import json
from types import SimpleNamespace

import pytest

from app.generation import GenerationError
from app.tutor_plan import PlanRequest, propose


class ScriptedModel:
    settings = SimpleNamespace(model="test-model")

    def __init__(self, *actions):
        self.actions = iter(actions)
        self.contexts = []

    async def _complete(self, payload, api_key):
        assert api_key == "synthetic-key"
        self.contexts.append(json.loads(payload["messages"][-1]["content"]))
        return json.dumps(next(self.actions))


def action(name, ids=(), reason=""):
    return {"ids": list(ids), "reason": reason}


def request():
    return PlanRequest.model_validate({"minutes": 6, "mastery": [], "candidates": [
        {"id": "due", "kind": "REVIEW", "topic": "隔离性", "material": "事务", "recentErrors": 0},
        {"id": "practice", "kind": "PRACTICE", "topic": "持久性", "material": "事务", "recentErrors": 2},
    ]})


def test_graph_plans_once_over_permission_checked_candidates():
    model = ScriptedModel(action("FINISH", ["due", "practice"], "先复习到期题"))
    output = asyncio.run(propose(model, request(), "synthetic-key"))
    assert output == {"ids": ["due", "practice"], "reason": "先复习到期题", "trace": ["SUMMARY"]}
    assert len(model.contexts) == 1
    assert len(model.contexts[0]["candidates"]) == 2


@pytest.mark.parametrize("invalid", [
    action("DUE"),
    action("FINISH", ["invented"], "未知题目"),
    action("FINISH", ["due", "due"], "重复题"),
])
def test_graph_rejects_empty_unknown_ids_and_duplicates(invalid):
    model = ScriptedModel(invalid)
    with pytest.raises(GenerationError, match="MODEL_OUTPUT_INVALID"):
        asyncio.run(propose(model, request(), "synthetic-key"))
