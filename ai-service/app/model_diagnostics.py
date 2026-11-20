"""协程局部的诊断标签；不记录用户 Key、请求正文或供应商原文。"""
from contextvars import ContextVar
import json
import re

trace_context = ContextVar("model_trace", default={})

def trace_fields():
    value = trace_context.get()
    result = {"stage": value.get("stage") if value.get("stage") in {"generate", "catalog"} else "other"}
    # 即使内部请求头被误填，也不能通过换行或任意字符串注入日志。
    if re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", value.get("job", "")):
        result["job"] = value["job"]
    if re.fullmatch(r"[0-9]{1,9}", value.get("attempt", "")):
        result["attempt"] = int(value["attempt"])
    return json.dumps(result, sort_keys=True)
