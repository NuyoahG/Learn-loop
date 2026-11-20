"""无状态 FSRS 调度。账号、作答和幂等记录由 Java 管理。"""
import json
from datetime import datetime, timezone
from typing import Annotated

from fsrs import Card, Rating, Scheduler
from pydantic import BaseModel, ConfigDict, Field


class ReviewRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    state: Annotated[str, Field(max_length=2000)] | None
    rating: Annotated[int, Field(ge=1, le=4)]
    reviewedAt: Annotated[str, Field(max_length=40)]


def schedule(payload: ReviewRequest) -> dict[str, str]:
    reviewed = datetime.fromisoformat(payload.reviewedAt)
    if reviewed.tzinfo is None:
        raise ValueError("Timezone required")
    reviewed = reviewed.astimezone(timezone.utc)
    card = Card(card_id=0, due=reviewed) if payload.state is None else Card.from_dict(json.loads(payload.state))
    # 相同卡片、评分和时刻得到相同结果，便于服务重试与回归验证。
    next_card, _ = Scheduler(enable_fuzzing=False).review_card(card, Rating(payload.rating), reviewed)
    return {"state": json.dumps(next_card.to_dict(), separators=(",", ":")),
            "dueAt": next_card.due.isoformat()}
