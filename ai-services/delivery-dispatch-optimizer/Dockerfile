# delivery-dispatch-optimizer 이미지(ADR-0058 결정 10·12): python:3.12-slim + PyTorch CPU, uv 잠금 파일 그대로 설치. 포트 8407.
# 두 단계: build(git·uv 로 설치, 가중치 내려받기) → run(가상환경·가중치·앱만 — git·uv·내려받기 캐시를 남기지 않는다).
# RouteFinder 가중치는 이미지에 넣는다(실행 때 인터넷 없이 뜬다).
# 기반 이미지는 digest 로 고정(PC 1 검토 L6, 2026-11-13 확인) — 올릴 때는 docker buildx imagetools inspect 로 새 digest
FROM ghcr.io/astral-sh/uv:0.12.22@sha256:f513a91fc62fe7c17567eee97230dd198e43edb8a9fbecca843714a4358fe1bc AS uv

FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b AS build
COPY --from=uv /uv /usr/local/bin/uv
# routefinder·rl4co 는 Git 커밋 고정 설치라 git 이 필요하다(build 단계에만)
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT=/app/.venv UV_NO_CACHE=1 UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
RUN /app/.venv/bin/python -c "from huggingface_hub import hf_hub_download; import shutil, os; os.makedirs('/models', exist_ok=True); \
shutil.copy(hf_hub_download(repo_id='ai4co/routefinder', filename='checkpoints/100/rf-transformer.ckpt', revision='4edf73d68a5915787dd2f81ba177d77e13317190'), '/models/rf-transformer.ckpt')"

FROM python:3.12-slim@sha256:5e2bdb1b0d542d5039cffb8181c65930093949194a65c365dfb1b0fe13a4fa5b
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
COPY --from=build /models /models
COPY app ./app
ENV PATH="/app/.venv/bin:$PATH" OPTIMIZER_MODEL_PATH=/models/rf-transformer.ckpt PYTHONDONTWRITEBYTECODE=1
RUN useradd --uid 10001 --no-create-home optimizer
USER optimizer
EXPOSE 8407
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8407/health', timeout=2)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8407", "--workers", "1"]
