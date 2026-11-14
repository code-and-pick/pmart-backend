"""RouteFinder 추가 학습(fine-tuning) — 우리 문제 모양에 맞춘다(ADR-0058, 사용자 2026-11-13 "추가학습해").

왜: 공개 가중치는 **경로 길이 합**을 줄이도록 배웠다. 열린 경로(OVRP)에서는 경로를 여럿 만들어도 손해가 없어 짧은 경로(1건 묶음)를 많이
만든다 — 라이더 수가 정해진 우리 문제에서는 남는 배송이 생긴다(`docs/delivery/ai-optimizer-evaluation.md`: 배정 22~23% 적음).

무엇을 바꾸나: 보상만 바꾼다. 비용 = Σ고객 도착(MFC 출발부터, dispatch BundleProblem 의 Σ도착과 같은 뜻) + λ × 경로 수(경로 하나 =
라이더 한 명 — 라이더가 MFC 까지 오는 비용과 라이더 부족을 함께 나타낸다). 문제는 서버 추론과 같은 방식으로 만든다(평가 도구의 문제 →
`_normalize` 좌표, `planner._deadlines` 지연 한도 시간창, 용량 1/3 = 최대 3건, 열린 경로).

학습: POMO(모든 고객에서 출발하는 다중 시작 샘플링, 공유 기준선 = 같은 문제 평균) REINFORCE, 공개 가중치에서 시작. CPU(스레드 제한).
결과는 서버가 읽는 형식({"state_dict": {"policy.*": 텐서}})으로 저장 — 제한 언피클러로 그대로 열린다.

    uv run python tools/finetune.py --steps 300 --out models/rf-pmart.ckpt
    OPTIMIZER_MODEL_PATH=models/rf-pmart.ckpt uv run python tools/evaluate.py --instances 60 --seed 1
"""

from __future__ import annotations

import argparse
import random
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import torch  # noqa: E402
from rl4co.utils.ops import batchify, unbatchify  # noqa: E402
from routefinder.envs.mtvrp import MTVRPEnv  # noqa: E402
from tensordict import TensorDict  # noqa: E402

import evaluate  # noqa: E402
from app import planner  # noqa: E402
from app.routefinder_model import RouteFinderModel, _normalize, checkpoint_path, road_route_cost, route_cost  # noqa: E402

MAX_SIZE = 3


class PmartEnv(MTVRPEnv):
    """보상 = −(Σ고객 도착 + λ × 경로 수). 도착은 MFC 출발부터의 누적 거리(속도 1, 평면 단위)."""

    def __init__(self, route_cost: float, **kwargs):
        super().__init__(**kwargs)
        self.route_cost = route_cost

    def _get_reward(self, td: TensorDict, actions: torch.Tensor) -> torch.Tensor:
        return -route_cost(td["locs"], actions, self.route_cost)


def instance_tensors(rnd: random.Random, n: int, pool=None):
    """평가 도구와 같은 분포의 문제 하나 → (좌표 [n+1,2], 시간창 [n+1,2], 도로 행렬 [n+1,n+1] 또는 None).
    pool 이 있으면 카카오 점 묶음에서 n 개를 뽑고 도로 행렬(평면 단위 — planner._road)을 같이 준다."""
    req = evaluate.instance(rnd, n, max(2, n // 3), pool=pool)
    mfc = (req.mfc.lat, req.mfc.lng)
    pts = [(d.at.lat, d.at.lng) for d in req.deliveries]
    locs = _normalize([mfc, *pts])
    idx = list(range(n))
    deadlines = planner._deadlines(req, idx, mfc, pts)
    tw = [[0.0, float("inf")]] + [[0.0, float(x)] for x in deadlines]
    road = planner._road(req, idx, mfc, pts) if pool is not None else None
    return locs, tw, road


def batch(rnd: random.Random, size: int, n: int, pool=None):
    """(TensorDict, 도로 행렬 [size, n+1, n+1] 또는 None)."""
    pairs = [instance_tensors(rnd, n, pool) for _ in range(size)]
    road = torch.tensor([p[2] for p in pairs], dtype=torch.float32) if pool is not None else None
    return TensorDict(
        {
            "locs": torch.tensor([p[0] for p in pairs], dtype=torch.float32),
            "demand_linehaul": torch.full((size, n), 1.0 / MAX_SIZE - 1e-6),
            "demand_backhaul": torch.zeros(size, n),
            "vehicle_capacity": torch.ones(size, 1),
            "capacity_original": torch.ones(size, 1),
            "open_route": torch.ones(size, 1, dtype=torch.bool),
            "time_windows": torch.tensor([p[1] for p in pairs], dtype=torch.float32),
            "service_time": torch.zeros(size, n + 1),
            "distance_limit": torch.full((size, 1), float("inf")),
            "speed": torch.ones(size, 1),
            "backhaul_class": torch.ones(size, 1),
        },
        batch_size=[size],
    ), road


def run(policy, env, td: TensorDict, n: int, train: bool):
    td = env.reset(td)
    out = policy(
        td,
        env,
        phase="train" if train else "test",
        decode_type="multistart_sampling" if train else "greedy",
        num_starts=n if train else 0,
        return_actions=True,
    )
    return out


def validate(policy, env, rnd_seed: int, per_route: float, count: int = 128, pool=None) -> float:
    """고정 문제(매번 같은 seed)에서 탐욕 디코딩 평균 비용 — 작을수록 좋다. pool 이면 학습에 안 쓴 점·도로 시간으로."""
    rnd = random.Random(rnd_seed)
    total = 0.0
    with torch.inference_mode():
        for n in (6, 9, 12):
            if pool is not None:
                n = min(n, len(pool[0]))
            td, road = batch(rnd, count // 3, n, pool)
            out = run(policy, env, td, n, train=False)
            cost = road_route_cost(road, out["actions"], per_route) if road is not None else -out["reward"]
            total += float(cost.sum())
    return total / (count // 3 * 3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=300)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=1e-5)
    ap.add_argument("--route-cost", type=float, default=1.0, help="경로(라이더) 하나의 비용 λ — 평면 단위(MFC 둘레 문제 폭 = 1)")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--val-every", type=int, default=50)
    ap.add_argument("--out", default="models/rf-pmart.ckpt")
    ap.add_argument("--pool-file", default=None, help="카카오 점 묶음(tools/kakao_pool.py) — 보상을 실제 도로 시간으로")
    ap.add_argument("--train-slice", default=None, help="학습에 쓸 점 a:b")
    ap.add_argument("--val-slice", default=None, help="검증(저장 판단)에 쓸 점 a:b — 학습 점과 겹치지 않게")
    ap.add_argument("--min-n", type=int, default=4)
    ap.add_argument("--max-n", type=int, default=12)
    args = ap.parse_args()
    train_pool = val_pool = None
    if args.pool_file:
        full = evaluate.load_pool(Path(args.pool_file))
        train_pool = evaluate.slice_pool(full, args.train_slice)
        val_pool = evaluate.slice_pool(full, args.val_slice)
        print(f"카카오 점 묶음 — 학습 점 {len(train_pool[0])}개, 검증 점 {len(val_pool[0])}개", flush=True)

    torch.manual_seed(args.seed)
    rnd = random.Random(args.seed)
    base = RouteFinderModel(checkpoint_path())
    torch.set_num_threads(args.threads)  # LAN 서비스와 함께 쓰는 PC — 스레드 제한
    policy = base._policy.train()
    env = PmartEnv(route_cost=args.route_cost)
    opt = torch.optim.Adam(policy.parameters(), lr=args.lr)

    best = validate(policy.eval(), env, 1234, args.route_cost, pool=val_pool)
    print(f"시작 검증 비용 {best:.4f}", flush=True)
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    for step in range(1, args.steps + 1):
        policy.train()
        n = rnd.randint(args.min_n, args.max_n)
        if train_pool is not None:
            n = min(n, len(train_pool[0]))
        td, road = batch(rnd, args.batch, n, train_pool)
        out = run(policy, env, td, n, train=True)
        if road is not None:  # 보상 = 실제 도로 시간 비용(평면 단위)
            flat = -road_route_cost(batchify(road, n), out["actions"], args.route_cost)
        else:
            flat = out["reward"]
        reward = unbatchify(flat, n)  # [batch, starts]
        ll = unbatchify(out["log_likelihood"], n)
        advantage = reward - reward.mean(dim=-1, keepdim=True)  # POMO 공유 기준선
        advantage = advantage / (advantage.std() + 1e-8)
        loss = -(advantage * ll).mean()
        opt.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(policy.parameters(), 1.0)
        opt.step()
        if step % 10 == 0:
            print(
                f"step {step} n={n} 평균 비용 {float(-reward.detach().mean()):.4f} 손실 {float(loss.detach()):.4f} "
                f"{(time.time() - t0) / step:.2f}s/step",
                flush=True,
            )
        if step % args.val_every == 0 or step == args.steps:
            v = validate(policy.eval(), env, 1234, args.route_cost, pool=val_pool)
            mark = ""
            if v < best:
                best = v
                torch.save({"state_dict": {"policy." + k: t for k, t in policy.state_dict().items()}}, out_path)
                mark = " — 저장"
            print(f"검증 step {step}: {v:.4f} (최고 {best:.4f}){mark}", flush=True)
    print(f"끝: 최고 검증 비용 {best:.4f}, 파일 {out_path if out_path.exists() else '없음(개선 없음)'}")


if __name__ == "__main__":
    main()
