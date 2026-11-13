# delivery-dispatch-optimizer 이미지(ADR-0058 결정 10·12): python:3.12-slim + PyTorch CPU, uv 잠금 파일 그대로 설치.
# RouteFinder 가중치는 빌드 때 받아 이미지에 넣는다(실행 때 인터넷 없이 뜬다). 포트 8407.
FROM ghcr.io/astral-sh/uv:0.12.22 AS uv

FROM python:3.12-slim
COPY --from=uv /uv /usr/local/bin/uv
# routefinder·rl4co 는 Git 커밋 고정 설치라 git 이 필요하다
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT=/app/.venv
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
ENV PATH="/app/.venv/bin:$PATH" OPTIMIZER_MODEL_PATH=/models/rf-transformer.ckpt
RUN python -c "from huggingface_hub import hf_hub_download; import shutil, os; os.makedirs('/models', exist_ok=True); \
shutil.copy(hf_hub_download(repo_id='ai4co/routefinder', filename='checkpoints/100/rf-transformer.ckpt'), '/models/rf-transformer.ckpt')" \
  && rm -rf /root/.cache/huggingface
COPY app ./app
RUN useradd --uid 10001 --no-create-home optimizer
USER optimizer
EXPOSE 8407
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8407/health', timeout=2)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8407", "--workers", "1"]
