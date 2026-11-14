"""평가용 실제 도로 시간 — 카카오모빌리티 다중 목적지 길찾기(`POST /v1/destinations/directions`).

delivery-tracking-service `KakaoMobilityClient` 와 같은 요청(priority TIME, radius 10000, 목적지 30개까지). 합성 좌표(MFC 둘레 무작위
점)만 보낸다 — 고객·라이더 위치가 아니다. 키는 파일·환경변수에서 읽고 출력·로그에 남기지 않는다.

같은 (출발, 도착) 쌍은 `.cache/kakao-legs.json` 에 저장해 다시 부르지 않는다(사용량 아끼기, git 제외).
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

URL = "https://apis-navi.kakaomobility.com/v1/destinations/directions"
CACHE = Path(__file__).resolve().parents[1] / ".cache" / "kakao-legs.json"
MAX_DEST = 30


def _k(p) -> str:
    return f"{p[0]:.6f},{p[1]:.6f}"


class KakaoRoad:
    def __init__(self, key: str, pause: float = 0.1, max_calls: int | None = None):
        self._key = key
        self._pause = pause
        self.calls = 0
        self._max_calls = max_calls  # 하루 1,000건(다중 목적지)을 LAN 배차와 나눠 쓴다 — 넘기 전에 멈춤
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        self._cache: dict[str, list[int] | None] = json.loads(CACHE.read_text("utf-8")) if CACHE.exists() else {}

    @staticmethod
    def from_file(path: str | None, max_calls: int | None = None) -> KakaoRoad:
        key = os.environ.get("KAKAO_MOBILITY_KEY")
        if not key and path:
            key = Path(path).read_text("utf-8").strip()
        if not key:
            raise SystemExit("카카오모빌리티 키가 없습니다(KAKAO_MOBILITY_KEY 또는 --kakao-key-file).")
        return KakaoRoad(key, max_calls=max_calls)

    def save(self) -> None:
        CACHE.write_text(json.dumps(self._cache), "utf-8")

    def seconds(self, origin, dests) -> list[int | None]:
        """출발 하나 → 여러 도착 도로 소요(초). 길 없음·반경 밖은 None."""
        out: list[int | None] = [None] * len(dests)
        todo = []
        for i, d in enumerate(dests):
            c = self._cache.get(_k(origin) + ">" + _k(d))
            if c is not None or (_k(origin) + ">" + _k(d)) in self._cache:
                out[i] = None if c is None else c[1]
            else:
                todo.append(i)
        for s in range(0, len(todo), MAX_DEST):
            chunk = todo[s : s + MAX_DEST]
            legs = self._call(origin, [dests[i] for i in chunk])
            for i, leg in zip(chunk, legs):
                self._cache[_k(origin) + ">" + _k(dests[i])] = leg
                out[i] = None if leg is None else leg[1]
            self.save()  # 중간에 멈춰도(사용량 소진 등) 받은 구간은 남긴다
        return out

    def _call(self, origin, dests) -> list[list[int] | None]:
        if self._max_calls is not None and self.calls >= self._max_calls:
            self.save()
            raise SystemExit(f"카카오 호출 상한 {self._max_calls}번에 닿아 멈춘다(받은 구간은 저장됨, --max-calls 로 조정).")
        body = {
            "origin": {"x": str(origin[1]), "y": str(origin[0])},
            "destinations": [{"x": str(d[1]), "y": str(d[0]), "key": str(i)} for i, d in enumerate(dests)],
            "radius": 10000,
            "priority": "TIME",
        }
        req = urllib.request.Request(
            URL,
            data=json.dumps(body).encode("utf-8"),
            headers={"Authorization": "KakaoAK " + self._key, "Content-Type": "application/json"},
            method="POST",
        )
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=10) as res:
                    data = json.loads(res.read().decode("utf-8"))
                break
            except urllib.error.HTTPError as e:
                if e.code == 429:
                    raise SystemExit("카카오모빌리티 사용량 소진(429) — 여기서 멈춘다(저장한 결과는 남는다).") from None
                if e.code in (401, 403):
                    raise SystemExit(f"카카오모빌리티 키 거절({e.code})") from None
                if e.code == 400:
                    msg = e.read().decode("utf-8", "replace")[:300]  # 오류 문구만(키·좌표 아님)
                    raise SystemExit(f"카카오모빌리티 요청 오류 400 — 목적지 {len(dests)}개: {msg}") from None
                if attempt == 2:
                    raise
                time.sleep(1 + attempt)
            except urllib.error.URLError:
                if attempt == 2:
                    raise
                time.sleep(1 + attempt)
        self.calls += 1
        time.sleep(self._pause)
        legs: list[list[int] | None] = [None] * len(dests)
        for r in data.get("routes", []):
            try:
                i = int(r.get("key", ""))
            except ValueError:
                continue
            if 0 <= i < len(dests) and r.get("result_code", -1) == 0:
                s = r.get("summary", {})
                legs[i] = [int(s.get("distance", 0)), int(s.get("duration", 0))]
        return legs
