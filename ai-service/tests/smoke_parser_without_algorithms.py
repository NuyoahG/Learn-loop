"""用未安装 pyBKT 的旧解释器验证新源码默认仍能提供原资料解析。

这是兼容性冒烟脚本，不启动常驻服务、不接 MySQL、不修改解释器环境。
从 ai-service 根目录用旧解释器加 -B 执行，避免在旧环境写字节码缓存。
"""

import importlib.util
import json
import os
from pathlib import Path
import sys


# 只把 staging 的新源码放入当前进程搜索路径，不复制包进旧虚拟环境。
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main():
    """默认关闭下验证 health、鉴权、两项新能力关闭响应和真实 TXT 解析。"""
    if importlib.util.find_spec("pyBKT") is not None:
        raise AssertionError("This smoke check requires an interpreter without pyBKT")
    os.environ.pop("LEARNLOOP_BKT_ENABLED", None)
    os.environ.pop("LEARNLOOP_GENERATION_ENABLED", None)

    from fastapi.testclient import TestClient
    from app.main import BKT_PATH, GENERATION_PATH, create_app

    key = "parser-compatibility-test-only-key-123456789"
    headers = {"X-LearnLoop-Service-Key": key}
    with TestClient(create_app(key)) as client:
        assert client.get("/health").json()["bktEnabled"] is False
        assert client.get("/health").json()["generationEnabled"] is False
        assert client.post(BKT_PATH, content=b"{broken").status_code == 401
        disabled = client.post(BKT_PATH, headers=headers, content=b"{broken")
        assert disabled.status_code == 503
        assert disabled.json()["errorCode"] == "BKT_NOT_ENABLED"
        assert client.post(GENERATION_PATH, content=b"{broken").status_code == 401
        generation_disabled = client.post(GENERATION_PATH, headers=headers, content=b"{broken")
        assert generation_disabled.status_code == 503
        assert generation_disabled.json()["errorCode"] == "GENERATION_NOT_ENABLED"
        parsed = client.post("/internal/parse", headers=headers,
                             data={"fileType": "txt", "attemptId": "compatibility:1"},
                             files={"file": ("compatibility.txt", b"Original parser remains available.")})
        assert parsed.status_code == 200
        assert parsed.json()["blocks"][0]["text"] == "Original parser remains available."
    assert "app.bkt" not in sys.modules
    assert "app.generation" not in sys.modules
    assert not any(name == "pyBKT" or name.startswith("pyBKT.") for name in sys.modules)
    print(json.dumps({"status": "passed", "pyBKTInstalled": False, "bktModuleImported": False,
                      "health": "UP", "unauthorizedBktStatus": 401, "disabledBktStatus": 503,
                      "generationModuleImported": False, "unauthorizedGenerationStatus": 401,
                      "disabledGenerationStatus": 503, "originalTxtParseStatus": 200}))


if __name__ == "__main__":
    main()
