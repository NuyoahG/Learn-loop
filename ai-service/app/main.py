"""本机内部服务：资料解析、真实 BKT 单步计算与来源约束出题。

所有 /internal 路径先认证，再读取和解包请求体；解析上传、BKT 与
出题请求分别限制 101 MiB、16 KiB 和 128 KiB。新能力默认关闭。
"""
import asyncio
import hmac
import json
import os
import re
import threading
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.datastructures import UploadFile
from starlette.concurrency import run_in_threadpool
from app.models import DOCUMENT_FILE_LIMIT, TEXT_FILE_LIMIT, VERSION, ParseError
from app.runner import run_uploaded_parse


BKT_PATH = "/internal/algorithms/bkt/step"
FSRS_PATH = "/internal/algorithms/fsrs/review"
GENERATION_PATH = "/internal/generation/questions"
CATALOG_PATH = "/internal/generation/catalog"
READING_PATH = "/internal/reading/answer"
TUTOR_PATH = "/internal/tutor/answer"
TUTOR_PLAN_PATH = "/internal/tutor/plan"
MODEL_PATHS = (GENERATION_PATH, CATALOG_PATH, READING_PATH, TUTOR_PATH, TUTOR_PLAN_PATH)
GENERATION_BODY_LIMIT = 128 * 1024
READING_BODY_LIMIT = 40 * 1024
PARSE_BODY_LIMIT = DOCUMENT_FILE_LIMIT + 1024 * 1024
BKT_BODY_LIMIT = 16 * 1024
BODY_READ_TIMEOUT_SECONDS = 15


class DuplicateJsonField(ValueError):
    """JSON 同名字段会被常规解包静默覆盖；严格内部契约主动拒绝它。"""


def unique_json_fields(pairs):
    """在 JSON 对象解包时拒绝重复键，不把键名或字段值写入异常文案。"""
    result = {}
    for name, value in pairs:
        if name in result:
            raise DuplicateJsonField("Duplicate JSON field")
        result[name] = value
    return result


def error(status: int, code: str, message: str):
    """只返回受控错误文案，避免把内部异常、原始请求或凭据带出服务。"""
    return JSONResponse({"errorCode": code, "message": message}, status_code=status)


def create_app(service_key: str | None = None, bkt_enabled: bool | None = None,
               generation_enabled: bool | None = None):
    """构造应用，BKT 默认关闭以兼容尚未安装算法依赖的既有解析环境。

    测试/嵌入调用可显式传 bool；None 时只把环境变量
    LEARNLOOP_BKT_ENABLED=true（忽略大小写与首尾空白）视为启用。
    关闭时绝不导入 app.bkt / pyBKT，也不检查算法依赖版本；因此仅
    写回新源码不会迫使现有解析服务更换或安装虚拟环境。
    """
    key = service_key if service_key is not None else os.getenv("LEARNLOOP_PARSER_SERVICE_KEY", "")
    enabled = (os.getenv("LEARNLOOP_BKT_ENABLED", "false").strip().lower() == "true"
               if bkt_enabled is None else bkt_enabled)
    if not isinstance(enabled, bool):
        raise TypeError("bkt_enabled must be bool or None")
    algorithm = None
    if enabled:
        # 只有显式启用后才加载重型算法依赖；enabled=False 不能走这里。
        # schema 也在此时注册，避免顶层类型注解隐式导入 app.bkt。
        from app import bkt
        algorithm = bkt

    generation_on = (os.getenv("LEARNLOOP_GENERATION_ENABLED", "false").strip().lower() == "true"
                     if generation_enabled is None else generation_enabled)
    if not isinstance(generation_on, bool):
        raise TypeError("generation_enabled must be bool or None")
    generation = None
    if generation_on:
        # 关闭状态不导入 httpx，也不要求存在模型地址、密钥或模型账号。
        from app import generation as generation_module
        generation = generation_module

    @asynccontextmanager
    async def lifespan(app):
        if len(key) < 32 or not key.isascii():
            raise RuntimeError("A service key of at least 32 ASCII characters is required")
        if enabled:
            algorithm.validate_library_versions()
        app.state.generation_client = None
        if generation_on:
            try:
                app.state.generation_client = generation.GenerationClient.from_environment()
            except ValueError:
                # 地址/模型或非空环境密钥配置有误时，仅该能力返回 503；
                # 空环境密钥合法，用户可由网页保存后让 Java 单次代传。
                # 原解析和 BKT 继续工作，且不把配置内容写入异常或响应。
                pass
        try:
            yield
        finally:
            if app.state.generation_client is not None:
                await app.state.generation_client.aclose()

    app = FastAPI(title="LearnLoop internal services", docs_url=None, redoc_url=None,
                  openapi_url=None, lifespan=lifespan)
    gate = threading.Lock()
    generation_gate = threading.Lock()

    @app.middleware("http")
    async def protect(request: Request, call_next):
        """在路由与 Pydantic 解包之前认证，按接口独立限制真实读取字节数。

        未知内部路径也必须携带有效凭据，避免后续新增接口遗漏保护。
        不信任 Content-Length，边读取边计数，并复用已读取 body 给下游。
        """
        path = request.url.path
        if path == "/internal" or path.startswith("/internal/"):
            supplied = request.headers.get("x-learnloop-service-key", "")
            if len(key) < 32 or not key.isascii() or not hmac.compare_digest(supplied.encode(), key.encode()):
                return error(401, "SERVICE_UNAUTHORIZED", "内部服务凭据无效。")
        normalized_path = path.rstrip("/")
        if normalized_path == BKT_PATH and not enabled:
            # 鉴权已经完成。关闭的能力不读取/解包正文，即使请求 JSON
            # 损坏也稳定返回 503；缺凭据仍优先得到上面的 401。
            return error(503, "BKT_NOT_ENABLED", "掌握度计算尚未启用，请先完成算法环境配置。")
        if normalized_path in MODEL_PATHS and not generation_on:
            return error(503, "GENERATION_NOT_ENABLED", "模型出题尚未启用，请先完成模型配置。")
        if normalized_path in MODEL_PATHS and generation_on:
            # 必须在服务间鉴权之后处理用户 Key，且先于读取正文。浏览器
            # 无法直接使用此内部入口；Java 负责按当前用户取出并代传 Key。
            # 拒绝重复头，避免不同 HTTP 层对“取第一个/最后一个”产生歧义。
            supplied_keys = request.headers.getlist("x-learnloop-model-key")
            if normalized_path in (READING_PATH, TUTOR_PATH, TUTOR_PLAN_PATH) and not supplied_keys:
                return error(409, "MODEL_NOT_CONFIGURED", "请先在网页中配置自己的模型 API Key。")
            if (len(supplied_keys) > 1
                    or (supplied_keys and not generation.valid_user_api_key(supplied_keys[0]))):
                return error(422, "INVALID_MODEL_KEY", "模型 API Key 格式无效，请重新输入。")
            request.state.model_api_key = supplied_keys[0] if supplied_keys else None
        limit = (BKT_BODY_LIMIT if normalized_path in (BKT_PATH, FSRS_PATH)
                 else GENERATION_BODY_LIMIT if normalized_path in (GENERATION_PATH, CATALOG_PATH)
                 else READING_BODY_LIMIT if normalized_path == READING_PATH
                 else GENERATION_BODY_LIMIT if normalized_path in (TUTOR_PATH, TUTOR_PLAN_PATH) else None)
        if limit is not None:
            body = bytearray()
            try:
                async with asyncio.timeout(BODY_READ_TIMEOUT_SECONDS):
                    async for chunk in request.stream():
                        if len(body) + len(chunk) > limit:
                            message = f"请求超过 {limit // 1024} KiB。"
                            return error(413, "REQUEST_TOO_LARGE", message)
                        body.extend(chunk)
            except TimeoutError:
                return error(408, "REQUEST_TIMEOUT", "上传读取超时。")
            request._body = bytes(body)
            if normalized_path in (BKT_PATH, FSRS_PATH) or normalized_path in MODEL_PATHS:
                # 两类 JSON 都已受独立字节上限保护，再拒绝重复键。
                # 真正字段校验交给路由 Pydantic 模型。认证已先完成。
                # 不接受“最后一个值覆盖前一个”的事件或出题参数歧义。
                try:
                    json.loads(request._body, object_pairs_hook=unique_json_fields)
                except DuplicateJsonField:
                    return error(422, "INVALID_REQUEST", "请求字段、类型或契约版本无效。")
                except (json.JSONDecodeError, UnicodeDecodeError, RecursionError):
                    # 普通损坏 JSON 保留框架既有的 422/400 行为；全局
                    # RequestValidationError 处理器不会回显原始 input。
                    pass
        return await call_next(request)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError):
        """严格契约失败时不回显 Pydantic 的 input 字段及原始 JSON 内容。"""
        return error(422, "INVALID_REQUEST", "请求字段、类型或契约版本无效。")

    @app.get("/health")
    def health():
        return {"status": "UP", "parserVersion": VERSION, "bktEnabled": enabled, "generationEnabled": generation_on}

    if enabled:
        @app.post(BKT_PATH, response_model=algorithm.BktStepResponse)
        def bkt_step(payload: algorithm.BktStepRequest):
            """接收 Java 合格观测，在工作线程执行真实计算，失败不暴露内部信息。"""
            try:
                return algorithm.calculate_bkt_step(payload)
            except Exception:
                return error(500, "BKT_CALCULATION_FAILED", "掌握度计算暂未完成，请稍后重试。")
    else:
        @app.post(BKT_PATH)
        def bkt_disabled():
            """保留关闭状态的能力入口；真正响应由先鉴权的中间件提前返回。"""
            return error(503, "BKT_NOT_ENABLED", "掌握度计算尚未启用，请先完成算法环境配置。")

    from app import fsrs_review

    @app.post(FSRS_PATH)
    def schedule_review(payload: fsrs_review.ReviewRequest):
        try:
            return fsrs_review.schedule(payload)
        except (ValueError, TypeError, KeyError, OverflowError):
            return error(422, "INVALID_REVIEW_STATE", "复习状态无效。")

    if generation_on:
        from app.tutor import TutorRequest, tutor_answer

        from app.tutor_plan import PlanRequest, propose

        @app.post(TUTOR_PLAN_PATH)
        async def plan_tutor(payload: PlanRequest, request: Request):
            client = app.state.generation_client
            if client is None or not generation_gate.acquire(blocking=False):
                return error(503, "GENERATION_BUSY", "暂时无法使用模型安排练习。")
            try:
                return await propose(client, payload, request.state.model_api_key)
            except generation.GenerationError as exc:
                return error(exc.status, exc.code, exc.message)
            except ImportError:
                return error(503, "MODEL_NOT_CONFIGURED", "练习规划依赖尚未安装。")
            finally:
                generation_gate.release()

        @app.post(TUTOR_PATH)
        async def answer_tutor(payload: TutorRequest, request: Request):
            client = app.state.generation_client
            if client is None:
                return error(503, "MODEL_NOT_CONFIGURED", "模型服务尚未配置。")
            if not generation_gate.acquire(blocking=False):
                return error(503, "GENERATION_BUSY", "模型正在处理另一项任务，请稍后重试。")
            try:
                return await tutor_answer(client, payload, request.state.model_api_key)
            except generation.GenerationError as exc:
                return error(exc.status, exc.code, exc.message)
            except ImportError:
                return error(503, "MODEL_NOT_CONFIGURED", "辅导编排依赖尚未安装。")
            finally:
                generation_gate.release()

        async def run_generation(operation, payload, request):
            """不排队的单并发出题入口，失败保持结构化错误且释放闸门。"""
            if app.state.generation_client is None:
                return error(503, "MODEL_NOT_CONFIGURED", "模型服务尚未配置，请检查服务端模型设置。")
            if not generation_gate.acquire(blocking=False):
                return error(503, "GENERATION_BUSY", "出题服务正忙，请稍后重试。")
            from app.model_diagnostics import trace_context
            token = trace_context.set({"stage": operation,
                "job": request.headers.get("x-learnloop-job", ""),
                "attempt": request.headers.get("x-learnloop-attempt", "")})
            try:
                method = getattr(app.state.generation_client, operation)
                return await method(payload, api_key=request.state.model_api_key)
            except generation.GenerationError as exc:
                return error(exc.status, exc.code, exc.message)
            except Exception:
                # 兜底仍不回显供应商异常、资料或本机路径；不能造题作为降级。
                return error(502, "MODEL_UPSTREAM_FAILED", "模型出题暂未完成，请稍后重试。")
            finally:
                trace_context.reset(token)
                generation_gate.release()

        @app.post(GENERATION_PATH, response_model=generation.GenerationResponse)
        async def generate_questions(payload: generation.GenerationRequest, request: Request):
            return await run_generation("generate", payload, request)

        @app.post(CATALOG_PATH, response_model=generation.CatalogResult)
        async def extract_catalog(payload: generation.CatalogRequest, request: Request):
            return await run_generation("catalog", payload, request)

        @app.post(READING_PATH)
        async def answer_reading(payload: generation.ReadingRequest, request: Request):
            if app.state.generation_client is None:
                return error(503, "MODEL_NOT_CONFIGURED", "模型服务尚未配置。")
            if not generation_gate.acquire(blocking=False):
                return error(503, "GENERATION_BUSY", "模型服务正在处理另一项任务，请稍后重试。")
            try:
                return await app.state.generation_client.explain(payload, request.state.model_api_key)
            except generation.GenerationError as exc:
                return error(exc.status, exc.code, exc.message)
            finally:
                generation_gate.release()
    else:
        @app.post(GENERATION_PATH)
        def generation_disabled():
            return error(503, "GENERATION_NOT_ENABLED", "模型出题尚未启用，请先完成模型配置。")

    @app.post("/internal/parse")
    async def parse_request(request: Request):
        if not gate.acquire(blocking=False):
            return error(503, "PARSER_BUSY", "解析服务正忙，请稍后重试。")
        try:
            # 对原始 multipart 按实际读取字节计数，包括 chunked 请求。
            # 大文件由 Starlette 分块写入临时文件，避免中间件将整份正文缓存在内存。
            length = request.headers.get("content-length", "")
            if length.isascii() and length.isdecimal() and (len(length) > 10 or int(length) > PARSE_BODY_LIMIT):
                raise ParseError(413, "REQUEST_TOO_LARGE", "上传请求超过 101 MiB。")
            received = 0
            deadline = asyncio.get_running_loop().time() + BODY_READ_TIMEOUT_SECONDS

            async def receive_limited():
                nonlocal received
                async with asyncio.timeout_at(deadline):
                    message = await request.receive()
                received += len(message.get("body", b""))
                if received > PARSE_BODY_LIMIT:
                    raise ParseError(413, "REQUEST_TOO_LARGE", "上传请求超过 101 MiB。")
                return message

            upload_request = Request(request.scope, receive=receive_limited)
            async with upload_request.form(max_files=1, max_fields=2, max_part_size=1024) as form:
                if sorted(k for k, v in form.multi_items()) != ["attemptId", "file", "fileType"]:
                    return error(400, "INVALID_FORM", "需要 file、fileType 和 attemptId 各一项。")
                upload, kind, attempt = form["file"], form["fileType"], form["attemptId"]
                if not isinstance(upload, UploadFile) or kind not in ("txt", "md", "docx", "pdf"):
                    return error(400, "INVALID_FORM", "文件或类型无效。")
                if not isinstance(attempt, str) or not re.fullmatch(r"[A-Za-z0-9:_-]{1,64}", attempt):
                    return error(400, "INVALID_ATTEMPT", "尝试标识无效。")
                cap = TEXT_FILE_LIMIT if kind in ("txt", "md") else DOCUMENT_FILE_LIMIT
                if upload.size is None or upload.size > cap:
                    return error(413, "FILE_TOO_LARGE", "文件超过此格式的大小限制。")
                result = await run_in_threadpool(run_uploaded_parse, upload.file, kind, attempt)
                return JSONResponse(result)
        except ParseError as exc:
            return error(exc.status, exc.code, exc.message)
        except TimeoutError:
            return error(408, "REQUEST_TIMEOUT", "上传读取超时。")
        except Exception:
            return error(400, "INVALID_FORM", "请求格式不正确或读取失败。")
        finally:
            gate.release()

    return app


app = create_app()
