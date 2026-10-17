-- ==================== 迁移：新增 Agent 运行审计表（阶段二） ====================
-- 使用方式: mysql -h 192.168.199.128 -u root -p < sql/migration-20260922-agent-run.sql
-- 可重复执行：CREATE TABLE IF NOT EXISTS 天然幂等。
--
-- 【为什么必须有这张表】
-- Agent 的回答是非确定性的：同一个问题，模型这次可能调 1 个工具、下次调 3 个，
-- 参数也可能不同。这意味着线上出问题时**无法靠复现来定位** ——
-- 唯一能查的就是"当时它到底做了什么"。因此每一步的
-- 工具名 / 参数 / 是否命中 / 耗时 / 中途停止原因，都必须落库。
--
-- 【与 t_ai_chat_history 的分工】
--   t_ai_chat_history：存"用户看到的那句话"（对话内容，供上下文回填与回看）
--   t_ai_agent_run   ：存"系统为这句话做了什么"（决策过程，供审计与排查）
-- 两者是同一轮问答的两个视角，刻意分开：对话历史是热路径（每次问答都读写），
-- 审计是冷路径（只写不读），混在一起会让热表越来越重。

CREATE TABLE IF NOT EXISTS t_ai_agent_run (
    id              BIGINT       AUTO_INCREMENT  PRIMARY KEY,
    user_id         BIGINT       NOT NULL        COMMENT '用户ID',
    session_id      VARCHAR(36)                  COMMENT '会话ID（UUID，可为空）',
    question        TEXT         NOT NULL        COMMENT '用户原始问题',
    steps_json      TEXT                         COMMENT 'Agent 步骤记录（JSON 数组：tool/arguments/ok/summary/elapsed_ms）',
    tools_used      VARCHAR(255)                 COMMENT '本次用到的工具名（逗号分隔，便于聚合查询）',
    steps_used      INT          DEFAULT 0       COMMENT '实际执行步数',
    tokens          INT          DEFAULT 0       COMMENT '累计 token（模型未返回 usage 时为 0）',
    latency_ms      BIGINT       DEFAULT 0       COMMENT '整轮耗时（毫秒）',
    stopped_by      VARCHAR(32)                  COMMENT '停止原因：completed/max_steps/deadline/budget/tool_error/error',
    data_source     VARCHAR(20)                  COMMENT '结果标记：milvus/llm_only/none/agent',
    degraded        TINYINT      DEFAULT 0       COMMENT '是否为降级结果：0-否 1-是',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX           idx_user_created (user_id, created_at),
    INDEX           idx_created (created_at),
    INDEX           idx_stopped_by (stopped_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 运行审计（阶段二）';

-- 校验
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_agent_run'
ORDER BY ORDINAL_POSITION;
