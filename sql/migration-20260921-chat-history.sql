-- ============================================================
-- 迁移脚本：AI 问答历史表（体验优化批次 C —— 对话记忆）
--
-- 为什么需要这张表：对话记忆先用 Redis（TTL 2 小时）做热层，但 Redis 过期/重启后
-- 用户就会"立刻失忆"—— 上一句刚说完深蹲，下一句问"那做几组"就答非所问。
-- 表作为长期层：热层未命中时从表里回填最近 6 轮，同时也让"回看历史"成为可能。
--
-- 用法：
--   mysql -h 192.168.199.128 -u root -p < sql/migration-20260921-chat-history.sql
--
-- CREATE TABLE IF NOT EXISTS 天然可重复执行（本脚本连跑两次不会报错）。
-- ============================================================

USE fitness_db;

CREATE TABLE IF NOT EXISTS t_ai_chat_history (
    id          BIGINT       AUTO_INCREMENT  PRIMARY KEY,
    user_id     BIGINT       NOT NULL        COMMENT '用户ID',
    session_id  VARCHAR(36)  NOT NULL        COMMENT '会话ID（UUID）',
    msg_role    VARCHAR(16)  NOT NULL        COMMENT '角色：user / assistant',
    content     TEXT         NOT NULL        COMMENT '消息内容',
    data_source VARCHAR(20)                  COMMENT 'assistant 行的来源标记：milvus/llm_only/builtin/none',
    degraded    TINYINT      DEFAULT 0       COMMENT 'assistant 行是否为降级回答：0-否 1-是',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX       idx_user_session (user_id, session_id, id),
    INDEX       idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI问答历史（长期记忆）';

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_chat_history'
ORDER BY ORDINAL_POSITION;
