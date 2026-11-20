"""稳定前缀、证据映射和有限扩展的模型边界测试；全部使用 HTTP 替身。"""
import json
import logging

import httpx
import pytest

from app import generation
from app.main import GENERATION_PATH, CATALOG_PATH
from tests.test_generation_api import (CONCEPT, HEADERS, TEXT, configured_client, envelope, item,
                                 request_payload, result_payload, variant_payload)


def test_sources_prefix_survives_difficulty_count_and_job_changes(monkeypatch):
    sent = []
    def handler(request):
        sent.append(json.loads(request.content))
        return httpx.Response(200, json=envelope(result_payload(items=[], shortfallReason="无新考点")))
    with configured_client(monkeypatch, handler) as client:
        for count, difficulty in ((3, "MEDIUM"), (10, "HARD")):
            assert client.post(GENERATION_PATH, headers=HEADERS,
                               json=request_payload(count=count, difficulty=difficulty)).status_code == 200
    assert sent[0]["messages"][0] == sent[1]["messages"][0]
    left, right = [value["messages"][1]["content"] for value in sent]
    assert left.split(',"count":')[0] == right.split(',"count":')[0]
    assert left.startswith('{"sources":') and "jobId" not in left and "attemptToken" not in left
    assert left.count(TEXT) == 1, "正文不能为了缓存重复发送两份"


def test_target_and_evidence_references_restore_server_metadata(monkeypatch):
    generated = item(conceptId=CONCEPT)
    for name in ("conceptId", "conceptName", "conceptDefinition", "blockId", "sourceQuote"):
        del generated[name]
    generated.update(targetRef="T1", evidenceRef="B7E1")
    with configured_client(monkeypatch, lambda _: httpx.Response(200, json=envelope(
            result_payload(items=[generated])))) as client:
        response = client.post(GENERATION_PATH, headers=HEADERS,
                               json=variant_payload(mode="TARGETED", targetConceptId=None))
    assert response.status_code == 200
    saved = response.json()["items"][0]
    assert saved["conceptId"] == CONCEPT and saved["blockId"] == 7
    assert saved["sourceQuote"] == TEXT
    assert "targetRef" not in saved and "evidenceRef" not in saved


@pytest.mark.parametrize("ref", ["B999E1", "B7E999"])
def test_model_cannot_invent_evidence(monkeypatch, ref):
    candidate = item(evidenceRef=ref)
    with configured_client(monkeypatch, lambda _: httpx.Response(200, json=envelope(
            result_payload(items=[candidate], shortfallReason="数量不足")))) as client:
        assert client.post(GENERATION_PATH, headers=HEADERS, json=request_payload()).status_code == 502


@pytest.mark.parametrize("difficulty,extensions,expected", [("HARD", 1, 200), ("MEDIUM", 1, 502), ("HARD", 2, 502)])
def test_extension_is_labeled_and_bounded(monkeypatch, difficulty, extensions, expected):
    candidates = [item(index) for index in range(3)]
    for candidate in candidates[:extensions]:
        candidate.update(extension=True, extensionNote="题干给出一个额外的并发条件")
    with configured_client(monkeypatch, lambda _: httpx.Response(200, json=envelope(
            result_payload(items=candidates)))) as client:
        response = client.post(GENERATION_PATH, headers=HEADERS, json=request_payload(difficulty=difficulty))
    assert response.status_code == expected
    if expected == 200:
        assert response.json()["items"][0]["content"].startswith("【扩展题】")
        assert "题干给出一个额外的并发条件" in response.json()["items"][0]["content"]
        assert "扩展前提" in response.json()["items"][0]["explanation"]
        assert "extension" not in response.json()["items"][0]


def test_ten_questions_still_use_one_generation_call(monkeypatch):
    calls = []
    def handler(request):
        calls.append(request)
        return httpx.Response(200, json=envelope(result_payload()))
    with configured_client(monkeypatch, handler) as client:
        assert client.post(GENERATION_PATH, headers=HEADERS, json=request_payload(count=10)).status_code == 502
    assert len(calls) == 1, "少题缺原因仍整批失败；不能偷偷切批、修复或再次计费"


def test_noise_view_keeps_original_source_and_cache_usage_is_measurable(monkeypatch, caplog):
    original = ("内部资料，仅供学习\n" * 8) + TEXT
    requests = []
    def handler(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=envelope(usage={"prompt_tokens": 1000,
            "prompt_cache_hit_tokens": 800, "prompt_cache_miss_tokens": 200}))
    caplog.set_level(logging.INFO, logger="uvicorn.error")
    with configured_client(monkeypatch, handler) as client:
        assert client.post(GENERATION_PATH, headers=HEADERS,
                           json=request_payload(sources=[{"blockId": 7, "text": original}])).status_code == 200
    upstream = requests[0]["messages"][1]["content"]
    assert "内部资料" not in upstream and TEXT in upstream
    assert original.startswith("内部资料")
    assert '"prompt_cache_hit_tokens": 800' in caplog.text
    assert "test-only-api-key" not in caplog.text and TEXT not in caplog.text


def test_catalog_evidence_and_explicit_empty_reasons(monkeypatch):
    entry = {"existingId": None, "evidenceRef": "B7E1", "chapter": "事务", "groupName": "ACID",
             "name": "原子性", "definition": "全部成功或全部回滚", "legacyIds": [],
             "aliases": ["事务原子性"], "granularity": "COARSE"}
    output = {"entries": [entry], "blocks": [{"blockId": 7, "status": "READY", "reason": "有可考查原理"}]}
    with configured_client(monkeypatch, lambda _: httpx.Response(200, json=envelope(output))) as client:
        response = client.post(CATALOG_PATH, headers=HEADERS,
                               json={"sources": [{"blockId": 7, "text": TEXT}], "topics": [], "legacy": []})
    assert response.status_code == 200
    assert response.json()["entries"][0]["quote"] == TEXT
    assert response.json()["entries"][0]["granularity"] == "COARSE"
