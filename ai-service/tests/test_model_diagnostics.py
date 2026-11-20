import asyncio
import json
from app.model_diagnostics import trace_context, trace_fields

def test_diagnostics_are_coroutine_local_and_discard_untrusted_headers():
    async def request(job, attempt):
        token=trace_context.set({"job":job,"attempt":attempt,"stage":"generate"})
        try:
            await asyncio.sleep(0)
            return json.loads(trace_fields())
        finally:
            trace_context.reset(token)
    async def run():
        return await asyncio.gather(request("12345678-1234-1234-1234-123456789abc","2"),
                                    request("secret\nFORGED","not-a-number"))
    first,second=asyncio.run(run())
    assert first["attempt"]==2 and "job" in first
    assert second=={"stage":"generate"}
    assert json.loads(trace_fields())=={"stage":"other"}
