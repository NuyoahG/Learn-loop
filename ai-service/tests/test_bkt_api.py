"""真实 BKT 内部接口：安全边界、数值语义、重放与并发隔离回归。

使用独立分数运算作数值参照，不 mock pyBKT 的计算结果。这里证明
接口契约与库调用成立；用户资格、事务、选题效果由 Java 集成验收。
"""

import asyncio
import json
from concurrent.futures import ThreadPoolExecutor
from fractions import Fraction

import pytest
import httpx
from fastapi.testclient import TestClient
from starlette.requests import Request

from app.main import BKT_BODY_LIMIT, BKT_PATH, create_app


KEY = "test-only-service-key-not-a-production-secret-1234"
HEADERS = {"X-LearnLoop-Service-Key": KEY, "Content-Type": "application/json"}


@pytest.fixture
def client():
    """每个测试有独立应用生命周期；测试密钥不依赖本机秘密配置。"""
    with TestClient(create_app(KEY, bkt_enabled=True)) as value:
        yield value


def request_payload(**updates):
    """构造契约最小请求；不包含任何用户身份、答案正文或数据库字段。"""
    value = {"schemaVersion": "1", "eventId": "evt-1", "modelVersion": "bkt-fixed-v1",
             "priorMastery": 0.2, "correct": True}
    value.update(updates)
    return value


def exact_step(prior, correct):
    """用标准库有理数给出三个时间点的独立数学预期。"""
    prior = Fraction(str(prior))
    predicted = prior * Fraction(".9") + (1 - prior) * Fraction(".25")
    posterior = (prior * Fraction(".9") / predicted if correct
                 else prior * Fraction(".1") / (1 - predicted))
    return tuple(float(v) for v in (predicted, posterior, posterior + (1 - posterior) * Fraction(".1")))


@pytest.mark.parametrize("path", [BKT_PATH, "/internal/parse", "/internal/unknown", "/internal"])
def test_every_internal_path_authenticates_before_json_parsing(client, monkeypatch, path):
    """即使 JSON 损坏，缺凭据也先 401；不能因模型解包而泄漏 422。

    把框架 JSON 解包替换成必抛错函数，证明未认证请求根本没有进入它。
    已知路由、未知路由、原解析接口都受同一鉴权边界保护。
    """
    async def must_not_parse_json(self):
        raise AssertionError("Unauthorized requests must not parse JSON")

    monkeypatch.setattr(Request, "json", must_not_parse_json)
    for headers in ({}, {"X-LearnLoop-Service-Key": "wrong"}):
        response = client.post(path, content=b"{broken", headers=headers)
        assert response.status_code == 401
        assert response.json()["errorCode"] == "SERVICE_UNAUTHORIZED"


def test_authorized_unknown_internal_path_is_404(client):
    """认证通过后未知路径正常 404，新增路由不会继承意外的无认证通道。"""
    assert client.post("/internal/unknown", headers=HEADERS).status_code == 404


def test_bkt_body_limit_is_exact_and_does_not_trust_content_length(client):
    """16 KiB 含填充空白仍可解包；超过 1 字节时在 JSON 解包之前拒绝。"""
    body = json.dumps(request_payload()).encode()
    exact_limit = body + b" " * (BKT_BODY_LIMIT - len(body))
    assert client.post(BKT_PATH, content=exact_limit, headers=HEADERS).status_code == 200
    oversized = exact_limit + b" "
    response = client.post(BKT_PATH, content=oversized, headers={**HEADERS, "Content-Length": "1"})
    assert response.status_code == 413
    assert response.json()["errorCode"] == "REQUEST_TOO_LARGE"
    # 未授权请求优先返回 401，不能通过 body 大小推测是否到达内部路由。
    assert client.post(BKT_PATH, content=oversized).status_code == 401


def test_chunked_request_cannot_bypass_size_limit(client):
    """没有 Content-Length 的流式请求也必须对累积字节数设限。"""
    chunks = (b" " * 4096 for _ in range(5))
    response = client.post(BKT_PATH, content=chunks, headers=HEADERS)
    assert response.status_code == 413


def test_slow_stream_hits_total_read_deadline_and_recovers(monkeypatch):
    """慢速分块不能无限占住读取；仅测试缩短截止，下一请求仍可用。"""
    import app.main as main

    monkeypatch.setattr(main, "BODY_READ_TIMEOUT_SECONDS", 0.02)

    async def run():
        async def stalled_body():
            yield b"{"
            await asyncio.sleep(0.1)
            yield b"}"

        transport = httpx.ASGITransport(app=create_app(KEY, bkt_enabled=True))
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as value:
            timed_out = await value.post(BKT_PATH, headers=HEADERS, content=stalled_body())
            assert timed_out.status_code == 408
            assert timed_out.json()["errorCode"] == "REQUEST_TIMEOUT"
            recovered = await value.post(BKT_PATH, headers=HEADERS, json=request_payload())
            assert recovered.status_code == 200

    asyncio.run(run())


@pytest.mark.parametrize("field,value", [("correct", "false"), ("priorMastery", "0.7"),
                                        ("eventId", '"other-event"')])
def test_duplicate_json_keys_cannot_silently_overwrite_observation(client, field, value):
    """重复键即使本身都合法，也不能靠“最后一个值生效”歪曲事件。"""
    body = json.dumps(request_payload())[:-1] + f',"{field}":{value}' + "}"
    response = client.post(BKT_PATH, content=body, headers=HEADERS)
    assert response.status_code == 422
    assert response.json()["errorCode"] == "INVALID_REQUEST"
    assert "other-event" not in response.text


@pytest.mark.parametrize("updates", [
    {"schemaVersion": 1}, {"schemaVersion": "2"}, {"modelVersion": "bkt-trained-v1"},
    {"eventId": ""}, {"eventId": "x" * 65}, {"eventId": "has space"}, {"eventId": 1},
    {"priorMastery": -0.01}, {"priorMastery": 1.01}, {"priorMastery": "0.2"},
    {"priorMastery": True}, {"priorMastery": None}, {"priorMastery": float("nan")},
    {"priorMastery": float("inf")}, {"priorMastery": -float("inf")},
    {"correct": 1}, {"correct": 0}, {"correct": -1}, {"correct": "true"},
    {"correct": None}, {"userId": 1}, {"eligible": True},
], ids=["schema-number", "schema-future", "model-future", "empty-id", "long-id", "space-id", "number-id",
        "prior-negative", "prior-large", "prior-string", "prior-bool", "prior-null", "prior-nan",
        "prior-inf", "prior-negative-inf", "correct-one", "correct-zero", "correct-missing-marker",
        "correct-string", "correct-null", "extra-user", "extra-eligibility"])
def test_strict_contract_rejects_invalid_fields(client, updates):
    """不让隐式转换、非有限数或客户端资格声明进入算法。"""
    # json.dumps 故意保留 Python 的 NaN/Infinity 输出，验证收到这类正文
    # 时服务也会拒绝，而不是只依赖测试客户端的严格 JSON 编码器。
    response = client.post(BKT_PATH, content=json.dumps(request_payload(**updates)), headers=HEADERS)
    assert response.status_code == 422
    assert response.json() == {"errorCode": "INVALID_REQUEST", "message": "请求字段、类型或契约版本无效。"}


@pytest.mark.parametrize("body", [b"{broken", b"[]", b"null", b"{}", b""])
def test_malformed_or_missing_json_returns_controlled_error(client, body):
    """只返回受控文案，不在验证错误里回显请求内容或本机路径。"""
    response = client.post(BKT_PATH, content=body, headers=HEADERS)
    assert response.status_code == 422
    assert "input" not in response.json()


def test_non_utf8_body_is_rejected_before_model_validation(client):
    """字节解码失败采用框架的 400，不把非法字节回显到错误响应。"""
    response = client.post(BKT_PATH, content=b"\xff", headers=HEADERS)
    assert response.status_code == 400
    assert response.json() == {"detail": "There was an error parsing the body"}


@pytest.mark.parametrize("prior", [0, 0.01, 0.2, 0.99, 1])
@pytest.mark.parametrize("correct", [False, True])
def test_real_library_matches_independent_golden_math(client, prior, correct):
    """真实 pyBKT 输出必须匹配独立数学预期，并明确当前/下一步时点。"""
    response = client.post(BKT_PATH, json=request_payload(priorMastery=prior, correct=correct), headers=HEADERS)
    assert response.status_code == 200
    result = response.json()
    predicted, posterior, next_mastery = exact_step(prior, correct)
    assert result == {
        "schemaVersion": "1", "eventId": "evt-1", "modelVersion": "bkt-fixed-v1",
        "priorMastery": prior, "predictedCorrect": pytest.approx(predicted, abs=1e-12),
        "posteriorMastery": pytest.approx(posterior, abs=1e-12),
        "masteryProbability": pytest.approx(next_mastery, abs=1e-12), "libraryVersion": "1.4.3",
    }


def test_replay_and_persisted_state_resume_without_hidden_learning(client):
    """同输入可重放，恢复后的序列与独立递推一致；无额外初始化学习步。"""
    state = 0.2
    for index, correct in enumerate((True, False, True, True, False)):
        payload = request_payload(eventId=f"resume:{index}", priorMastery=state, correct=correct)
        first = client.post(BKT_PATH, json=payload, headers=HEADERS)
        repeated = client.post(BKT_PATH, json=payload, headers=HEADERS)
        assert first.status_code == repeated.status_code == 200
        assert first.json() == repeated.json()
        expected = exact_step(state, correct)[2]
        state = json.loads(json.dumps(first.json()))["masteryProbability"]
        assert state == pytest.approx(expected, abs=1e-12)


def test_concurrent_requests_never_share_mutable_roster_state(client):
    """交错不同先验和对错，逐响应核对 eventId 与概率，捕捉状态串用。"""
    jobs = [(index, (index % 11) / 10, bool(index % 2)) for index in range(44)]

    def send(job):
        index, prior, correct = job
        response = client.post(BKT_PATH, json=request_payload(eventId=f"parallel-{index}",
                               priorMastery=prior, correct=correct), headers=HEADERS)
        assert response.status_code == 200
        return job, response.json()

    with ThreadPoolExecutor(max_workers=8) as executor:
        results = list(executor.map(send, jobs))
    for (index, prior, correct), result in results:
        assert result["eventId"] == f"parallel-{index}"
        assert result["masteryProbability"] == pytest.approx(exact_step(prior, correct)[2], abs=1e-12)


def test_calculation_failure_is_controlled_and_does_not_poison_next_request(client, monkeypatch):
    """内部异常不回显；下一次请求重新构造状态，仍可执行。"""
    import app.bkt as bkt

    original = bkt.calculate_bkt_step

    def fail_once(payload):
        raise RuntimeError("sensitive internal path must not be returned")

    monkeypatch.setattr(bkt, "calculate_bkt_step", fail_once)
    response = client.post(BKT_PATH, json=request_payload(), headers=HEADERS)
    assert response.status_code == 500
    assert response.json()["errorCode"] == "BKT_CALCULATION_FAILED"
    assert "sensitive" not in response.text
    monkeypatch.setattr(bkt, "calculate_bkt_step", original)
    assert client.post(BKT_PATH, json=request_payload(), headers=HEADERS).status_code == 200


@pytest.mark.parametrize("package,bad_version", [("pyBKT", "1.4.4"), ("scikit-learn", "1.9.1")])
def test_unverified_runtime_version_refuses_startup(monkeypatch, package, bad_version):
    """版本升级必须先回归，不能让不兼容适配器带病启动后才污染概率。"""
    import app.bkt as bkt

    actual_version = bkt.version
    monkeypatch.setattr(bkt, "version", lambda name: bad_version if name == package else actual_version(name))
    with pytest.raises(RuntimeError, match="BKT runtime versions"):
        with TestClient(create_app(KEY, bkt_enabled=True)):
            pass


def test_default_disabled_keeps_auth_first_and_returns_503_without_json_parse(monkeypatch):
    """默认关闭不要求算法环境；认证通过后拒绝能力请求，且不解包正文。"""
    monkeypatch.delenv("LEARNLOOP_BKT_ENABLED", raising=False)

    async def must_not_parse_json(self):
        raise AssertionError("Disabled BKT must not parse JSON")

    monkeypatch.setattr(Request, "json", must_not_parse_json)
    with TestClient(create_app(KEY)) as value:
        assert value.get("/health").json()["bktEnabled"] is False
        assert value.post(BKT_PATH, content=b"{broken").status_code == 401
        response = value.post(BKT_PATH, headers=HEADERS, content=b"{broken")
        assert response.status_code == 503
        assert response.json()["errorCode"] == "BKT_NOT_ENABLED"


def test_disabled_factory_never_imports_algorithm_module(monkeypatch):
    """即使算法模块已被其他测试加载，关闭分支也不能再次发起算法导入。"""
    import builtins

    original_import = builtins.__import__

    def guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "app.bkt" or name.startswith("pyBKT") or name == "app" and "bkt" in fromlist:
            raise AssertionError("Disabled BKT must not import algorithm libraries")
        return original_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", guarded_import)
    with TestClient(create_app(KEY, bkt_enabled=False)) as value:
        assert value.get("/health").status_code == 200
        assert value.post(BKT_PATH, headers=HEADERS, json=request_payload()).status_code == 503


def test_environment_switch_enables_bkt_but_explicit_false_takes_precedence(monkeypatch):
    """运行脚本可通过环境启用，显式 False 仍能构造纯解析测试实例。"""
    monkeypatch.setenv("LEARNLOOP_BKT_ENABLED", "true")
    with TestClient(create_app(KEY)) as enabled:
        assert enabled.get("/health").json()["bktEnabled"] is True
        assert enabled.post(BKT_PATH, headers=HEADERS, json=request_payload()).status_code == 200
    with TestClient(create_app(KEY, bkt_enabled=False)) as disabled:
        assert disabled.get("/health").json()["bktEnabled"] is False
        assert disabled.post(BKT_PATH, headers=HEADERS, json=request_payload()).status_code == 503
