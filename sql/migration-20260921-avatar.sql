-- ============================================================
-- 迁移脚本：用户头像（批次 B）
--
-- 背景：`spring.jpa.hibernate.ddl-auto: none` —— 表结构以 SQL 为唯一事实来源，
-- 而 init.sql 用的是 `CREATE TABLE IF NOT EXISTS`，对**已存在的库**不会补列。
-- 因此存量库必须单独执行本脚本，新装库直接由 init.sql 建全。
--
-- 用法：
--   mysql -h 192.168.199.128 -u root -p < sql/migration-20260921-avatar.sql
--
-- ⚠️ MySQL 8 的 ADD COLUMN **不支持 IF NOT EXISTS**（那是 MariaDB 的扩展），
--    所以这里用 information_schema 先判断再决定是否执行，保证脚本可以重复运行
--    （否则第二次执行会直接报 1060 Duplicate column name）。
-- ============================================================

USE fitness_db;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_user'
      AND COLUMN_NAME = 'avatar_url'
);

SET @ddl := IF(@column_exists = 0,
    'ALTER TABLE t_user ADD COLUMN avatar_url VARCHAR(255) NULL COMMENT ''头像访问路径（含版本号，为空表示未设置）'' AFTER training_level',
    'SELECT ''t_user.avatar_url 已存在，跳过'' AS notice');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'avatar_url';
