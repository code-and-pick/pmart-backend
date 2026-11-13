"""RouteFinder(ai4co, MIT) 경로 모델 — 알뜰배달 고객을 MFC 출발·열린 경로(OVRP)·용량 max_bundle_size 로 묶고 방문 순서를 낸다.

가중치: HF ``ai4co/routefinder`` ``checkpoints/100/rf-transformer.ckpt``(100노드 학습, ADR-0058 결정 11).

안전한 적재: 이 체크포인트는 하이퍼파라미터에 클래스 객체(옛 torchrl 등)를 피클로 담고 있어 ``weights_only=True`` 로는 열리지 않는다.
``weights_only=False`` 는 임의 코드 실행 위험이 있어 쓰지 않는다 — 텐서 복원 함수만 허용하고 나머지 전역은 아무 코드도 실행하지 않는 빈
객체로 바꾸는 제한 언피클러로 ``state_dict`` 만 꺼내, 모델은 코드로 만들고(정규화 rms·병렬 게이트 MLP·prenorm·마지막 정규화)
가중치를 엄격히(strict) 넣는다.
"""

from __future__ import annotations

import logging
import math
import os
import pickle
import time
import types
from collections.abc import Sequence

log = logging.getLogger(__name__)

HF_REPO = "ai4co/routefinder"
HF_FILE = "checkpoints/100/rf-transformer.ckpt"
NAME = "ROUTEFINDER rf-transformer-100"

_TENSOR_GLOBALS = {
    ("torch._utils", "_rebuild_tensor_v2"),
    ("torch._utils", "_rebuild_parameter"),
    ("collections", "OrderedDict"),
    ("torch", "Size"),
}
_BUILTINS = {"dict", "list", "tuple", "set", "frozenset", "int", "float", "str", "bool"}


class _Inert:
    """체크포인트의 알 수 없는 객체 자리 — 생성·상태 설정만 받고 아무것도 하지 않는다."""

    def __init__(self, *args, **kwargs):
        pass

    def __setstate__(self, state):
        pass

    def __call__(self, *args, **kwargs):
        return _Inert()


class _TensorOnlyUnpickler(pickle.Unpickler):
    def find_class(self, module, name):
        if (module, name) in _TENSOR_GLOBALS or (module == "builtins" and name in _BUILTINS):
            return super().find_class(module, name)
        if module in ("torch", "torch.storage") and name.endswith("Storage"):
            return super().find_class(module, name)
        return _Inert


_SAFE_PICKLE = types.SimpleNamespace(Unpickler=_TensorOnlyUnpickler, load=pickle.load, __name__="pmart_tensor_only_pickle")


def checkpoint_path() -> str:
    """OPTIMIZER_MODEL_PATH(이미지에 미리 받아 둔 파일)가 있으면 그것, 없으면 HF 에서 받는다."""
    p = os.environ.get("OPTIMIZER_MODEL_PATH")
    if p and os.path.exists(p):
        return p
    from huggingface_hub import hf_hub_download

    return hf_hub_download(repo_id=HF_REPO, filename=HF_FILE)


class RouteFinderModel:
    """multistart: 고객마다 출발점을 바꿔 n개를 한꺼번에 풀고(배치 — 한 번 풀기의 약 1.3배 시간) route_cost 비용식으로 가장 싼 답을 고른다."""

    def __init__(self, path: str, multistart: bool = False, route_cost: float = 2.0):
        import torch
        from routefinder.envs.mtvrp import MTVRPEnv
        from routefinder.models import RouteFinderPolicy

        t0 = time.time()
        ck = torch.load(path, map_location="cpu", weights_only=False, pickle_module=_SAFE_PICKLE)
        state = {k[len("policy.") :]: v for k, v in ck["state_dict"].items() if k.startswith("policy.")}
        policy = RouteFinderPolicy(
            normalization="rms",
            parallel_gated_kwargs={},
            encoder_use_post_layers_norm=True,
            encoder_use_prenorm=True,
        )
        policy.load_state_dict(state, strict=True)
        self._policy = policy.eval()
        self._env = MTVRPEnv()
        self._torch = torch
        self._multistart = multistart
        self._route_cost = route_cost
        torch.set_num_threads(max(1, min(4, os.cpu_count() or 1)))
        log.info("RouteFinder 적재 %.1f초 (%s)", time.time() - t0, NAME)

    def __call__(
        self,
        mfc: tuple[float, float],
        points: Sequence[tuple[float, float]],
        max_size: int,
        deadlines: Sequence[float] | None = None,
    ) -> list[list[int]]:
        """deadlines[i] = 고객 i 도착 한도를 [0,1] 평면 거리 단위로(속도 1). None 이면 시간창 없음."""
        torch = self._torch
        from tensordict import TensorDict

        n = len(points)
        locs = _normalize([mfc, *points])
        td = TensorDict(
            {
                "locs": torch.tensor([locs], dtype=torch.float32),
                # 3건이면 1/3 — 부동소수 합이 용량 1 을 넘지 않게 아주 조금 줄인다
                "demand_linehaul": torch.full((1, n), 1.0 / max_size - 1e-6),
                "demand_backhaul": torch.zeros(1, n),
                "vehicle_capacity": torch.ones(1, 1),
                "capacity_original": torch.ones(1, 1),
                "open_route": torch.ones(1, 1, dtype=torch.bool),
                "time_windows": _windows(torch, n, deadlines),
                "service_time": torch.zeros(1, n + 1),
                "distance_limit": torch.full((1, 1), float("inf")),
                "speed": torch.ones(1, 1),
                "backhaul_class": torch.ones(1, 1),
            },
            batch_size=[1],
        )
        with torch.inference_mode():
            td = self._env.reset(td)
            if self._multistart and n > 1:
                out = self._policy(
                    td, self._env, phase="test", decode_type="multistart_greedy", num_starts=n, return_actions=True
                )
                acts = out["actions"]  # [n, 길이] — 출발 고객마다 한 줄
                cost = route_cost(td["locs"].expand(acts.size(0), -1, -1), acts, self._route_cost)
                best = acts[int(cost.argmin())]
            else:
                out = self._policy(td, self._env, phase="test", decode_type="greedy", return_actions=True)
                best = out["actions"][0]
        routes: list[list[int]] = []
        cur: list[int] = []
        for a in best.tolist():
            if a == 0:
                if cur:
                    routes.append(cur)
                cur = []
            else:
                cur.append(a - 1)  # 고객 번호(0부터)
        if cur:
            routes.append(cur)
        return routes


def route_cost(locs, actions, per_route: float):
    """우리 비용(추가 학습 보상과 같다): Σ고객 도착(MFC 출발부터 누적 거리, 열린 경로) + per_route × 경로 수. 배치 [b]."""
    import torch
    from rl4co.utils.ops import gather_by_index, get_distance

    go_from = torch.cat((torch.zeros_like(actions[:, :1]), actions[:, :-1]), dim=1)
    go_to = actions
    d = get_distance(gather_by_index(locs, go_from), gather_by_index(locs, go_to))
    cum = torch.zeros(actions.size(0), device=locs.device)
    arrivals = torch.zeros_like(cum)
    for t in range(actions.size(1)):
        at_customer = go_to[:, t] != 0
        cum = torch.where(at_customer, cum + d[:, t], torch.zeros_like(cum))
        arrivals = arrivals + torch.where(at_customer, cum, torch.zeros_like(cum))
    routes = ((go_from == 0) & (go_to != 0)).sum(-1).float()
    return arrivals + per_route * routes


def _windows(torch, n: int, deadlines: Sequence[float] | None):
    tw = torch.tensor([[0.0, float("inf")]]).repeat(1, n + 1, 1)
    if deadlines is not None:
        for i, d in enumerate(deadlines):
            tw[0, i + 1, 1] = float(d)
    return tw


def normalized_span_m(latlng: Sequence[tuple[float, float]]) -> float:
    """_normalize 의 축척(미터) — [0,1] 한 칸이 몇 미터인지."""
    lat0 = sum(p[0] for p in latlng) / len(latlng)
    k = math.cos(math.radians(lat0)) * 111_320.0
    xs = [p[1] * k for p in latlng]
    ys = [p[0] * 110_574.0 for p in latlng]
    return max(max(xs) - min(xs), max(ys) - min(ys)) or 1.0


def _normalize(latlng: Sequence[tuple[float, float]]) -> list[list[float]]:
    """위경도 → 같은 축척의 [0,1] 평면(경도는 cos(위도)로 줄인다). 학습 데이터가 [0,1] 정사각형이다."""
    lat0 = sum(p[0] for p in latlng) / len(latlng)
    k = math.cos(math.radians(lat0))
    xy = [((p[1]) * k, p[0]) for p in latlng]
    xs = [p[0] for p in xy]
    ys = [p[1] for p in xy]
    span = max(max(xs) - min(xs), max(ys) - min(ys)) or 1.0
    return [[(x - min(xs)) / span, (y - min(ys)) / span] for x, y in xy]
