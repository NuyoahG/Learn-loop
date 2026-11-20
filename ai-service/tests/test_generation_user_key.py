"""网页个人模型 Key 的内部代传回归，全部使用 MockTransport，无真实计费。

浏览器 Key 由 Java 按用户解密后转成内部请求头；Python 不保存用户身份
或凭据，仅对当前调用组装 Authorization。测试中特意轮换两个标记 Key，
检查共享连接池不会跨请求遗留凭据，也不会把凭据拼入模型提示或错误。
"""
import asyncio
import json
import logging

import httpx
import pytest
from fastapi.testclient import TestClient

from app import generation
from app.main import GENERATION_PATH, create_app
from tests.test_generation_api import HEADERS, KEY, configured_client, envelope, request_payload


USER_A = "test-user-a-only-key-1234567890"
USER_B = "test-user-b-only-key-1234567890"
HEADER = "X-LearnLoop-Model-Key"


def test_no_environment_key_still_constructs_client_for_user_header(monkeypatch):
    """走真实工厂与生命周期，不依赖环境全局 Key 才能构造客户端。"""
    for name in ("LEARNLOOP_LLM_BASE_URL", "LEARNLOOP_LLM_MODEL", "LEARNLOOP_LLM_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    real_constructor = generation.GenerationClient.__init__
    seen = []

    def handler(request):
        seen.append(request.headers["authorization"])
        return httpx.Response(200, json=envelope())

    def mock_transport_constructor(self, settings, transport=None):
        real_constructor(self, settings, transport=httpx.MockTransport(handler))

    monkeypatch.setattr(generation.GenerationClient, "__init__", mock_transport_constructor)
    with TestClient(create_app(KEY, bkt_enabled=False, generation_enabled=True)) as client:
        assert client.app.state.generation_client is not None
        assert client.app.state.generation_client.settings.api_key == ""
        response = client.post(GENERATION_PATH, headers={**HEADERS, HEADER: USER_A}, json=request_payload())
        assert response.status_code == 200
        assert USER_A not in response.text
    assert seen == ["Bearer " + USER_A]


def test_rotating_personal_keys_never_mutates_settings_or_falls_back_to_other_user(monkeypatch):
    seen = []

    def handler(request):
        seen.append(request.headers["authorization"])
        prompt = json.dumps(json.loads(request.content), ensure_ascii=False)
        assert USER_A not in prompt and USER_B not in prompt
        return httpx.Response(200, json=envelope())

    with configured_client(monkeypatch, handler, api_key="") as client:
        adapter = client.app.state.generation_client
        for key in (USER_A, USER_B):
            response = client.post(GENERATION_PATH, headers={**HEADERS, HEADER: key}, json=request_payload())
            assert response.status_code == 200
            assert key not in response.text
        # 没有个人 Key 的下一次请求必须失败，不能继承刚完成的 B 用户。
        missing = client.post(GENERATION_PATH, headers=HEADERS, json=request_payload())
        assert missing.status_code == 503 and missing.json()["errorCode"] == "MODEL_NOT_CONFIGURED"
        assert adapter.settings.api_key == ""
        assert "authorization" not in adapter.client.headers
    assert seen == ["Bearer " + USER_A, "Bearer " + USER_B]


def test_legacy_environment_fallback_is_restored_without_reusing_personal_key(monkeypatch):
    seen = []

    def handler(request):
        seen.append(request.headers["authorization"])
        return httpx.Response(200, json=envelope())

    with configured_client(monkeypatch, handler) as client:
        assert client.post(GENERATION_PATH, headers=HEADERS, json=request_payload()).status_code == 200
        assert client.post(GENERATION_PATH, headers={**HEADERS, HEADER: USER_A}, json=request_payload()).status_code == 200
        assert client.post(GENERATION_PATH, headers=HEADERS, json=request_payload()).status_code == 200
        assert client.app.state.generation_client.settings.api_key == "test-only-api-key"
    assert seen == ["Bearer test-only-api-key", "Bearer " + USER_A, "Bearer test-only-api-key"]


@pytest.mark.parametrize("invalid", [b"", b"x" * 15, b"x" * 513, b" " + b"x" * 20,
    b"x" * 20 + b" ", b"x" * 8 + b"\t" + b"x" * 8, b"x" * 8 + b"\n" + b"x" * 8,
    b"x" * 8 + b"\r" + b"x" * 8, b"x" * 8 + b"\x00" + b"x" * 8,
    b"x" * 8 + b"\x7f" + b"x" * 8, b"x" * 8 + b"\xff" + b"x" * 8])
def test_invalid_user_header_rejected_before_body_or_provider_without_echo(monkeypatch, invalid):
    def fail(_):
        raise AssertionError("Invalid header must not call provider")

    with configured_client(monkeypatch, fail) as client:
        response = client.post(GENERATION_PATH,
                               headers=[(name.encode(), value.encode()) for name, value in HEADERS.items()]
                               + [(HEADER.encode(), invalid)], content=b"{broken-json")
    assert response.status_code == 422 and response.json()["errorCode"] == "INVALID_MODEL_KEY"
    assert response.json() == {"errorCode": "INVALID_MODEL_KEY", "message": "模型 API Key 格式无效，请重新输入。"}


def test_repeated_personal_key_headers_are_rejected(monkeypatch):
    def fail(_):
        raise AssertionError("Ambiguous headers must not call provider")

    with configured_client(monkeypatch, fail) as client:
        response = client.post(GENERATION_PATH, headers=list(HEADERS.items())
                               + [(HEADER, USER_A), (HEADER.lower(), USER_B)], json=request_payload())
    assert response.status_code == 422 and response.json()["errorCode"] == "INVALID_MODEL_KEY"
    assert USER_A not in response.text and USER_B not in response.text


@pytest.mark.parametrize("length", [16, 512])
def test_user_key_length_boundaries_are_sent_only_in_authorization(monkeypatch, length):
    personal = "x" * length

    def handler(request):
        assert request.headers["authorization"] == "Bearer " + personal
        assert personal.encode() not in request.content
        return httpx.Response(200, json=envelope())

    with configured_client(monkeypatch, handler, api_key="") as client:
        response = client.post(GENERATION_PATH, headers={**HEADERS, HEADER: personal}, json=request_payload())
    assert response.status_code == 200 and personal not in response.text


def test_internal_auth_still_precedes_invalid_user_key_and_body(monkeypatch):
    with configured_client(monkeypatch) as client:
        response = client.post(GENERATION_PATH, headers={HEADER: "bad"}, content=b"{broken-json")
    assert response.status_code == 401 and response.json()["errorCode"] == "SERVICE_UNAUTHORIZED"


def test_invalid_provider_address_cannot_be_bypassed_by_valid_personal_key(monkeypatch):
    monkeypatch.setenv("LEARNLOOP_LLM_BASE_URL", "https://localhost")
    monkeypatch.delenv("LEARNLOOP_LLM_API_KEY", raising=False)
    with TestClient(create_app(KEY, bkt_enabled=False, generation_enabled=True)) as client:
        assert client.app.state.generation_client is None
        response = client.post(GENERATION_PATH, headers={**HEADERS, HEADER: USER_A}, json=request_payload())
        assert client.get("/health").json()["status"] == "UP"
    assert response.status_code == 503 and response.json()["errorCode"] == "MODEL_NOT_CONFIGURED"


@pytest.mark.parametrize("environment_key", [" ", "bad key", "bad\nkey", "密钥", "x" * 4097])
def test_nonempty_invalid_environment_key_still_fails_closed(environment_key):
    with pytest.raises(ValueError, match="Invalid model service configuration") as caught:
        generation.GenerationClient(generation.ProviderSettings("https://api.deepseek.com", "deepseek-flash", environment_key))
    assert str(caught.value) == "Invalid model service configuration"


def test_settings_repr_excludes_even_the_environment_key():
    assert USER_A not in repr(generation.ProviderSettings("https://api.deepseek.com", "deepseek-flash", USER_A))


def test_personal_key_never_in_error_or_application_logs_and_is_not_retained(monkeypatch, caplog):
    caplog.set_level(logging.DEBUG)

    def handler(request):
        # 模拟供应商客户端异常包含凭据；服务必须替换为固定错误文案。
        raise httpx.ConnectError("provider error with " + USER_A, request=request)

    with configured_client(monkeypatch, handler, api_key="") as client:
        failed = client.post(GENERATION_PATH, headers={**HEADERS, HEADER: USER_A}, json=request_payload())
        missing = client.post(GENERATION_PATH, headers=HEADERS, json=request_payload())
    assert failed.status_code == 502 and failed.json()["errorCode"] == "MODEL_UPSTREAM_FAILED"
    assert missing.status_code == 503
    assert USER_A not in failed.text and USER_A not in caplog.text


def test_direct_concurrent_client_calls_keep_authorization_local():
    """连接池本身也应无共享凭据，不能只依赖目前路由的单并发闸门。"""
    seen = []

    async def handler(request):
        seen.append(request.headers["authorization"])
        await asyncio.sleep(.01)
        return httpx.Response(200, json=envelope())

    async def run():
        adapter = generation.GenerationClient(generation.ProviderSettings("https://api.deepseek.com", "deepseek-flash"),
                                              transport=httpx.MockTransport(handler))
        try:
            payload = generation.GenerationRequest.model_validate(request_payload())
            results = await asyncio.gather(adapter.generate(payload, USER_A), adapter.generate(payload, USER_B))
            assert len(results) == 2 and adapter.settings.api_key == ""
            with pytest.raises(generation.GenerationError) as caught:
                await adapter.generate(payload)
            assert caught.value.code == "MODEL_NOT_CONFIGURED"
        finally:
            await adapter.aclose()

    asyncio.run(run())
    assert sorted(seen) == sorted(["Bearer " + USER_A, "Bearer " + USER_B])


def test_disabled_parser_and_bkt_do_not_require_any_personal_model_key(monkeypatch):
    monkeypatch.delenv("LEARNLOOP_LLM_API_KEY", raising=False)
    with TestClient(create_app(KEY, bkt_enabled=False, generation_enabled=False)) as client:
        assert client.get("/health").json()["status"] == "UP"
        # 错误用户模型头不属于解析/BKT 契约，不能改变它们的既有行为。
        parsed = client.post("/internal/parse", headers={"X-LearnLoop-Service-Key": KEY, HEADER: "bad"},
                             files={"file": ("sample.txt", "测试资料正文。".encode(), "text/plain")},
                             data={"fileType": "txt", "attemptId": "user-model-key-parser-test"})
        assert parsed.status_code == 200
        disabled = client.post("/internal/algorithms/bkt/step", headers=HEADERS, json={})
        assert disabled.status_code == 503 and disabled.json()["errorCode"] == "BKT_NOT_ENABLED"


def test_personal_header_is_not_part_of_public_json_schema(monkeypatch):
    with configured_client(monkeypatch) as client:
        schema = client.app.openapi()
    text = json.dumps(schema)
    assert HEADER.lower() not in text.lower()
    assert "apiKey" not in generation.GenerationRequest.model_fields
