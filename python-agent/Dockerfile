# ==================== Python AI Agent 生产镜像 ====================
#
# ⚠️ 与规范示例的差异：基础镜像用 python:3.12-slim 而不是 3.10-slim。
# 本机实测与 requirements 都以 3.12 为准（README 也写「Python 3.12 推荐」），
# pymilvus 2.6 客户端与服务端 2.6.x 同代，换 3.10 反而多一处未验证的组合。
FROM python:3.12-slim

WORKDIR /app

ENV TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    PIP_NO_CACHE_DIR=1

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 先装依赖：requirements.txt 不变时可以复用这一层缓存
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 只复制运行期真正需要的目录：
# - app/        服务代码
# - data/       知识库种子数据（启动时幂等导入 Milvus）
# - scripts/    运维/验收脚本（可在容器内跑 ingest_knowledge.py 排查知识库）
# 不复制 tests/（体积大且运行期用不到），也不复制 .env（见 .dockerignore，含真实密钥）
COPY app/ ./app/
COPY data/ ./data/
COPY scripts/ ./scripts/

COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 8000

ENTRYPOINT ["/docker-entrypoint.sh"]
