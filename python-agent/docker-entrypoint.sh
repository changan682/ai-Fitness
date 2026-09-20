#!/bin/bash
# ==================== Python AI Agent 容器入口 ====================
#
# 职责：等依赖的中间件就绪后再启动 FastAPI。
# 为什么要等：容器启动顺序不等于依赖就绪顺序。Milvus 首次启动要几十秒，
# 若此时 FastAPI 已经起来，@app.on_event("startup") 里的知识库初始化会失败
# （虽然它是幂等的、不阻断启动，但会在日志里刷一堆连接错误，掩盖真正的问题）。
set -e

MILVUS_HOST="${MILVUS_HOST:-milvus}"
MILVUS_PORT="${MILVUS_PORT:-19530}"
MAX_WAIT_SECONDS="${MILVUS_WAIT_SECONDS:-180}"

echo "⏳ 等待 Milvus 就绪 (${MILVUS_HOST}:${MILVUS_PORT})，最长 ${MAX_WAIT_SECONDS}s..."

waited=0
until python -c "
import socket, sys
s = socket.socket()
s.settimeout(2)
try:
    s.connect(('${MILVUS_HOST}', int('${MILVUS_PORT}')))
    s.close()
except Exception:
    sys.exit(1)
" 2>/dev/null; do
  if [ "$waited" -ge "$MAX_WAIT_SECONDS" ]; then
    echo "❌ 等待 Milvus 超时（${MAX_WAIT_SECONDS}s）。请确认 milvus 容器状态与 MILVUS_HOST 配置。" >&2
    exit 1
  fi
  echo "   Milvus 尚未就绪，5 秒后重试... (${waited}s)"
  sleep 5
  waited=$((waited + 5))
done

echo "✅ Milvus 端口已就绪"

# 知识库初始化在 app.main 的 startup 钩子里自动执行（幂等，已导入则跳过）
exec uvicorn app.main:app --host 0.0.0.0 --port 8000
