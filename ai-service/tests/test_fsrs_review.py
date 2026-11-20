from datetime import datetime
from fastapi.testclient import TestClient
from app.main import create_app, FSRS_PATH


def test_first_review_and_resumed_card_use_real_fsrs():
    key = "test-only-fsrs-internal-key-1234567890"
    with TestClient(create_app(key, bkt_enabled=False, generation_enabled=False)) as client:
        headers = {"X-LearnLoop-Service-Key": key}
        request = {"state": None, "rating": 1, "reviewedAt": "2026-11-05T03:30:00Z"}
        assert client.post(FSRS_PATH, json=request).status_code == 401
        initial = client.post(FSRS_PATH, headers=headers, json=request)
        assert initial.status_code == 200
        assert initial.json() == client.post(FSRS_PATH, headers=headers, json=request).json()
        next_time = initial.json()["dueAt"]
        resumed = client.post(FSRS_PATH, headers=headers, json={
            "state": initial.json()["state"], "rating": 4, "reviewedAt": next_time
        })
        assert resumed.status_code == 200
        assert datetime.fromisoformat(resumed.json()["dueAt"]) > datetime.fromisoformat(next_time)
