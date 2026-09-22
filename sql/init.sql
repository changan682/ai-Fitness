-- ==================== 健身私教系统 - 数据库初始化脚本 ====================
-- 使用方式: mysql -h 192.168.199.128 -u root -p < sql/init.sql
-- 字符集: utf8mb4, 排序规则: utf8mb4_unicode_ci

CREATE DATABASE IF NOT EXISTS fitness_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE fitness_db;

-- ==================== 1. 用户表 ====================
CREATE TABLE IF NOT EXISTS t_user (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    nickname        VARCHAR(50)     NOT NULL        COMMENT '昵称',
    gender          TINYINT         DEFAULT 0       COMMENT '性别：0-未设置 1-男 2-女',
    birth_date      DATE                            COMMENT '出生日期',
    height          DECIMAL(5,1)                    COMMENT '身高(cm)',
    weight          DECIMAL(5,1)                    COMMENT '体重(kg)',
    training_goal   VARCHAR(20)     DEFAULT '保持'  COMMENT '训练目标：增肌/减脂/保持',
    training_level  VARCHAR(10)     DEFAULT '新手'  COMMENT '训练年限：新手/进阶/老手',
    avatar_url      VARCHAR(255)                    COMMENT '头像访问路径（含版本号，为空表示未设置）',
    injury_record   TEXT                            COMMENT '伤病记录（JSON数组字符串）',
    phone           VARCHAR(20)     NOT NULL        COMMENT '手机号',
    password        VARCHAR(255)    NOT NULL        COMMENT '密码（BCrypt加密）',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY      uk_phone (phone),
    INDEX           idx_nickname (nickname),
    INDEX           idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ==================== 2. 训练记录表 ====================
CREATE TABLE IF NOT EXISTS t_training_record (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    user_id         BIGINT          NOT NULL        COMMENT '用户ID',
    training_date   DATE            NOT NULL        COMMENT '训练日期',
    action_name     VARCHAR(100)    NOT NULL        COMMENT '动作名称（如：杠铃卧推）',
    sets            INT             NOT NULL        COMMENT '组数',
    reps            INT             NOT NULL        COMMENT '次数/组',
    weight_kg       DECIMAL(6,1)    NOT NULL DEFAULT 0.0 COMMENT '重量(kg)',
    duration_min    INT                             COMMENT '训练时长(分钟)',
    rpe             TINYINT                         COMMENT '主观感受RPE(1-10)',
    volume          DECIMAL(10,1)   NOT NULL DEFAULT 0.0 COMMENT '训练容量=组数×次数×重量（冗余字段）',
    remark          VARCHAR(500)                    COMMENT '备注',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX           idx_user_id (user_id),
    INDEX           idx_user_date (user_id, training_date),
    INDEX           idx_user_action (user_id, action_name),
    INDEX           idx_training_date (training_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='训练记录表';

-- ==================== 3. 身体数据追踪表 ====================
CREATE TABLE IF NOT EXISTS t_body_metric (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    user_id         BIGINT          NOT NULL        COMMENT '用户ID',
    record_date     DATE            NOT NULL        COMMENT '记录日期',
    weight_kg       DECIMAL(5,1)                    COMMENT '体重(kg)',
    waist_cm        DECIMAL(5,1)                    COMMENT '腰围(cm)',
    arm_cm          DECIMAL(5,1)                    COMMENT '臂围(cm)',
    leg_cm          DECIMAL(5,1)                    COMMENT '腿围(cm)',
    body_fat_pct    DECIMAL(4,1)                    COMMENT '体脂率(%)，可选',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX           idx_user_id (user_id),
    INDEX           idx_user_date (user_id, record_date),
    UNIQUE KEY      uk_user_date (user_id, record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='身体数据追踪表';

-- ==================== 4. 饮食记录表 ====================
CREATE TABLE IF NOT EXISTS t_diet_record (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    user_id         BIGINT          NOT NULL        COMMENT '用户ID',
    record_date     DATE            NOT NULL        COMMENT '记录日期',
    meal_type       VARCHAR(10)     NOT NULL        COMMENT '餐次：早餐/午餐/晚餐/加餐',
    food_name       VARCHAR(100)    NOT NULL        COMMENT '食物名称',
    weight_g        INT             NOT NULL        COMMENT '重量(克)',
    calories_kcal   DECIMAL(7,1)    NOT NULL        COMMENT '热量(kcal)',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX           idx_user_id (user_id),
    INDEX           idx_user_date (user_id, record_date),
    INDEX           idx_record_date (record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='饮食记录表';

-- ==================== 5. 食物热量库表 ====================
CREATE TABLE IF NOT EXISTS t_food_library (
    id                  BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    food_name           VARCHAR(100)    NOT NULL        COMMENT '食物名称',
    category            VARCHAR(20)     NOT NULL        COMMENT '分类：主食/肉类/蔬菜/水果/乳制品/零食/饮品',
    calories_per_100g   DECIMAL(6,1)    NOT NULL        COMMENT '每100g热量(kcal)',
    protein_per_100g    DECIMAL(5,1)    DEFAULT 0.0     COMMENT '每100g蛋白质(g)',
    fat_per_100g        DECIMAL(5,1)    DEFAULT 0.0     COMMENT '每100g脂肪(g)',
    carbs_per_100g      DECIMAL(5,1)    DEFAULT 0.0     COMMENT '每100g碳水(g)',
    created_at          DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY          uk_food_name (food_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='食物热量库表';

-- 初始化30种常见食物
INSERT INTO t_food_library (food_name, category, calories_per_100g, protein_per_100g, fat_per_100g, carbs_per_100g) VALUES
('米饭(熟)',   '主食', 116, 2.6, 0.3, 25.9),
('馒头',       '主食', 223, 7.0, 1.1, 44.2),
('面条(煮)',   '主食', 110, 3.5, 0.3, 22.0),
('全麦面包',   '主食', 246, 10.6, 3.4, 42.7),
('燕麦片',     '主食', 377, 13.5, 6.7, 61.6),
('红薯',       '主食', 86,  1.6, 0.1, 20.1),
('鸡胸肉',     '肉类', 133, 27.3, 3.0, 0.0),
('鸡腿肉',     '肉类', 181, 19.7, 11.2, 0.0),
('牛肉(瘦)',   '肉类', 125, 22.3, 4.0, 0.2),
('猪里脊',     '肉类', 155, 20.3, 7.9, 0.8),
('鸡蛋(煮)',   '肉类', 151, 12.1, 10.5, 0.9),
('三文鱼',     '肉类', 139, 17.2, 7.8, 0.0),
('虾仁',       '肉类', 99,  20.3, 0.7, 1.5),
('西兰花',     '蔬菜', 34,  3.5, 0.4, 2.7),
('菠菜',       '蔬菜', 23,  2.9, 0.4, 2.9),
('番茄',       '蔬菜', 18,  0.9, 0.2, 3.5),
('黄瓜',       '蔬菜', 16,  0.7, 0.1, 2.9),
('胡萝卜',     '蔬菜', 41,  1.0, 0.2, 8.8),
('香蕉',       '水果', 93,  1.4, 0.2, 20.8),
('苹果',       '水果', 53,  0.4, 0.2, 12.3),
('橙子',       '水果', 47,  1.0, 0.2, 10.1),
('蓝莓',       '水果', 57,  0.7, 0.3, 14.0),
('牛奶(全脂)', '乳制品', 61, 3.0, 3.2, 5.0),
('酸奶(原味)', '乳制品', 63, 3.5, 1.4, 9.3),
('奶酪',       '乳制品', 328, 25.7, 23.9, 1.7),
('豆腐',       '蔬菜', 82,  6.6, 4.5, 3.5),
('杏仁',       '零食', 578, 21.3, 50.6, 7.5),
('蛋白粉',     '饮品', 380, 75.0, 3.3, 5.0),
('橄榄油',     '饮品', 884, 0.0, 100.0, 0.0),
('蜂蜜',       '饮品', 321, 0.4, 0.0, 80.3)
ON DUPLICATE KEY UPDATE food_name=VALUES(food_name);

-- ==================== 6. 训练计划模板主表 ====================
CREATE TABLE IF NOT EXISTS t_workout_plan_template (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    template_name   VARCHAR(50)     NOT NULL        COMMENT '模板名称（如：三分化训练）',
    description     VARCHAR(500)                    COMMENT '模板描述',
    target_level    VARCHAR(10)     DEFAULT '新手'  COMMENT '适合人群：新手/进阶/老手',
    split_type      VARCHAR(20)     NOT NULL        COMMENT '分化方式：三分化/推拉腿/五分化/全身',
    is_active       TINYINT         DEFAULT 1       COMMENT '是否启用：0-禁用 1-启用',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY      uk_template_name (template_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='训练计划模板主表';

-- ==================== 7. 模板动作明细表 ====================
CREATE TABLE IF NOT EXISTS t_template_exercise (
    id                  BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    template_id         BIGINT          NOT NULL        COMMENT '模板ID',
    day_of_cycle        TINYINT         NOT NULL        COMMENT '周期内第几天（1-7）',
    day_label           VARCHAR(20)     NOT NULL        COMMENT '训练日标签（如：胸+三头）',
    action_name         VARCHAR(100)    NOT NULL        COMMENT '动作名称',
    target_muscle       VARCHAR(50)     NOT NULL        COMMENT '目标肌群（胸/背/腿/肩/手臂/核心）',
    recommended_sets    VARCHAR(20)     DEFAULT '3-4'   COMMENT '推荐组数范围',
    recommended_reps    VARCHAR(20)     DEFAULT '8-12'  COMMENT '推荐次数范围',
    sort_order          INT             DEFAULT 0       COMMENT '排序号',
    notes               VARCHAR(300)                    COMMENT '动作要点',
    created_at          DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX               idx_template_id (template_id),
    INDEX               idx_target_muscle (target_muscle),
    UNIQUE KEY          uk_template_day_action (template_id, day_of_cycle, action_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模板动作明细表';

-- ==================== 8. 用户训练安排表 ====================
CREATE TABLE IF NOT EXISTS t_user_workout_schedule (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    user_id         BIGINT          NOT NULL        COMMENT '用户ID',
    schedule_date   DATE            NOT NULL        COMMENT '安排日期',
    template_id     BIGINT                          COMMENT '来源模板ID',
    action_name     VARCHAR(100)    NOT NULL        COMMENT '动作名称',
    target_muscle   VARCHAR(50)                     COMMENT '目标肌群',
    target_sets     INT             DEFAULT 3       COMMENT '目标组数',
    target_reps     INT             DEFAULT 10      COMMENT '目标次数',
    is_completed    TINYINT         DEFAULT 0       COMMENT '是否完成：0-未完成 1-已完成',
    completed_at    DATETIME                        COMMENT '完成时间',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX           idx_user_date (user_id, schedule_date),
    INDEX           idx_template_id (template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户训练安排表';

-- ==================== 9. AI周计划表 ====================
CREATE TABLE IF NOT EXISTS t_weekly_plan (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    user_id         BIGINT          NOT NULL        COMMENT '用户ID',
    task_id         VARCHAR(64)     NOT NULL        COMMENT 'MQ任务ID（用于幂等去重）',
    week_start      DATE            NOT NULL        COMMENT '周起始日期（周一）',
    suggestion_text TEXT            NOT NULL        COMMENT 'AI生成的周计划建议（Markdown格式）',
    week_summary    TEXT                            COMMENT '本周训练数据摘要（JSON）',
    is_read         TINYINT         DEFAULT 0       COMMENT '用户是否已读：0-未读 1-已读',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY      uk_task_id (task_id),
    INDEX           idx_user_week (user_id, week_start),
    INDEX           idx_week_start (week_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI周计划表';

-- ==================== 10. AI总结缓存表 ====================
CREATE TABLE IF NOT EXISTS t_ai_summary_cache (
    id              BIGINT          AUTO_INCREMENT  PRIMARY KEY,
    user_id         BIGINT          NOT NULL        COMMENT '用户ID',
    summary_date    DATE            NOT NULL        COMMENT '总结日期',
    summary_text    TEXT            NOT NULL        COMMENT 'AI训练总结（Markdown）',
    input_snapshot  TEXT                            COMMENT '输入数据快照（JSON）',
    created_at      DATETIME        DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY      uk_user_date (user_id, summary_date),
    INDEX           idx_summary_date (summary_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI总结缓存表';

-- ==================== 11. 训练计划模板种子数据 ====================
-- 预设三种模板（提示词第一阶段「训练计划模板模块」）：
--   1) 三分化训练  7天周期：胸+三头 / 背+二头 / 腿+肩 / 休息 / 循环三次 → 6 个训练日
--   2) 推拉腿训练  7天周期：推 / 拉 / 腿 / 休息 / 循环三次 → 6 个训练日
--   3) 五分化训练  7天周期：胸 / 背 / 腿 / 肩 / 手臂 / 核心 / 休息 → 6 个训练日
-- 休息日不写明细行，套用模板时按 day_of_cycle 分组自然跳过。
-- 幂等：可重复执行，依赖 uk_template_name 与 uk_template_day_action 唯一索引。

INSERT INTO t_workout_plan_template (id, template_name, description, target_level, split_type, is_active) VALUES
(1, '三分化训练', '经典推-拉-腿三分化，每周循环两次，适合新手到进阶', '新手', '三分化', 1),
(2, '推拉腿训练', 'PPL 推-拉-腿六分化，容量与强度更高，适合进阶训练者', '进阶', '推拉腿', 1),
(3, '五分化训练', '胸/背/腿/肩/手臂五分化 + 核心日，适合老手精细打磨弱项', '老手', '五分化', 1)
ON DUPLICATE KEY UPDATE
    description  = VALUES(description),
    target_level = VALUES(target_level),
    split_type   = VALUES(split_type),
    is_active    = VALUES(is_active);

-- ---------- 模板1：三分化训练 ----------
INSERT INTO t_template_exercise
    (template_id, day_of_cycle, day_label, action_name, target_muscle, recommended_sets, recommended_reps, sort_order, notes) VALUES
-- 第1天 / 第5天：胸+三头
(1, 1, '胸+三头', '杠铃卧推',     '胸',   '3-4', '8-12',  1, '肩胛骨收紧下沉，杠铃下落至胸部中下沿'),
(1, 1, '胸+三头', '上斜哑铃卧推', '胸',   '3-4', '10-12', 2, '斜板约30度，避免耸肩，顶峰不锁肘'),
(1, 1, '胸+三头', '绳索夹胸',     '胸',   '3',   '12-15', 3, '肘部微屈固定，顶峰收缩1秒'),
(1, 1, '胸+三头', '窄距卧推',     '手臂', '3',   '10-12', 4, '握距与肩同宽，肘部贴近体侧'),
(1, 1, '胸+三头', '绳索下压',     '手臂', '3',   '12-15', 5, '大臂夹紧体侧，仅前臂发力'),
-- 第2天 / 第6天：背+二头
(1, 2, '背+二头', '引体向上',     '背',   '3-4', '6-10',  1, '全程控制，禁止摆荡借力'),
(1, 2, '背+二头', '杠铃划船',     '背',   '3-4', '8-12',  2, '背部平直，拉向肚脐方向'),
(1, 2, '背+二头', '高位下拉',     '背',   '3',   '10-12', 3, '下拉至锁骨，肘部向下向后'),
(1, 2, '背+二头', '杠铃弯举',     '手臂', '3',   '10-12', 4, '肘部固定不摆动，下放至完全伸展'),
(1, 2, '背+二头', '锤式弯举',     '手臂', '3',   '12-15', 5, '对握握法，强化肱桡肌'),
-- 第3天 / 第7天：腿+肩
(1, 3, '腿+肩',   '深蹲',         '腿',   '3-4', '8-12',  1, '膝盖与脚尖同向，髋部先向后下沉'),
(1, 3, '腿+肩',   '腿举',         '腿',   '3',   '10-15', 2, '不要锁死膝关节，保持张力'),
(1, 3, '腿+肩',   '罗马尼亚硬拉', '腿',   '3',   '8-12',  3, '髋铰链主导，感受腘绳肌拉伸'),
(1, 3, '腿+肩',   '站姿推举',     '肩',   '3-4', '8-12',  4, '核心收紧，避免腰部过伸代偿'),
(1, 3, '腿+肩',   '侧平举',       '肩',   '3',   '12-15', 5, '小重量，肘略高于腕，控制离心'),
-- 第4天为休息日（不写明细行）
(1, 5, '胸+三头', '杠铃卧推',     '胸',   '3-4', '8-12',  1, '肩胛骨收紧下沉，杠铃下落至胸部中下沿'),
(1, 5, '胸+三头', '上斜哑铃卧推', '胸',   '3-4', '10-12', 2, '斜板约30度，避免耸肩，顶峰不锁肘'),
(1, 5, '胸+三头', '绳索夹胸',     '胸',   '3',   '12-15', 3, '肘部微屈固定，顶峰收缩1秒'),
(1, 5, '胸+三头', '窄距卧推',     '手臂', '3',   '10-12', 4, '握距与肩同宽，肘部贴近体侧'),
(1, 5, '胸+三头', '绳索下压',     '手臂', '3',   '12-15', 5, '大臂夹紧体侧，仅前臂发力'),
(1, 6, '背+二头', '引体向上',     '背',   '3-4', '6-10',  1, '全程控制，禁止摆荡借力'),
(1, 6, '背+二头', '杠铃划船',     '背',   '3-4', '8-12',  2, '背部平直，拉向肚脐方向'),
(1, 6, '背+二头', '高位下拉',     '背',   '3',   '10-12', 3, '下拉至锁骨，肘部向下向后'),
(1, 6, '背+二头', '杠铃弯举',     '手臂', '3',   '10-12', 4, '肘部固定不摆动，下放至完全伸展'),
(1, 6, '背+二头', '锤式弯举',     '手臂', '3',   '12-15', 5, '对握握法，强化肱桡肌'),
(1, 7, '腿+肩',   '深蹲',         '腿',   '3-4', '8-12',  1, '膝盖与脚尖同向，髋部先向后下沉'),
(1, 7, '腿+肩',   '腿举',         '腿',   '3',   '10-15', 2, '不要锁死膝关节，保持张力'),
(1, 7, '腿+肩',   '罗马尼亚硬拉', '腿',   '3',   '8-12',  3, '髋铰链主导，感受腘绳肌拉伸'),
(1, 7, '腿+肩',   '站姿推举',     '肩',   '3-4', '8-12',  4, '核心收紧，避免腰部过伸代偿'),
(1, 7, '腿+肩',   '侧平举',       '肩',   '3',   '12-15', 5, '小重量，肘略高于腕，控制离心')
ON DUPLICATE KEY UPDATE
    day_label        = VALUES(day_label),
    target_muscle    = VALUES(target_muscle),
    recommended_sets = VALUES(recommended_sets),
    recommended_reps = VALUES(recommended_reps),
    sort_order       = VALUES(sort_order),
    notes            = VALUES(notes);

-- ---------- 模板2：推拉腿训练 ----------
INSERT INTO t_template_exercise
    (template_id, day_of_cycle, day_label, action_name, target_muscle, recommended_sets, recommended_reps, sort_order, notes) VALUES
(2, 1, '推(胸肩三头)', '杠铃卧推',       '胸',   '4',   '6-10',  1, '主项动作，组间休息2-3分钟'),
(2, 1, '推(胸肩三头)', '上斜哑铃推举',   '胸',   '3',   '8-12',  2, '上胸发力，肘部约45度夹角'),
(2, 1, '推(胸肩三头)', '坐姿推举',       '肩',   '3-4', '8-12',  3, '背部贴紧靠垫，不要过度挺腰'),
(2, 1, '推(胸肩三头)', '侧平举',         '肩',   '3',   '12-15', 4, '小重量多次数，控制离心'),
(2, 1, '推(胸肩三头)', '绳索下压',       '手臂', '3',   '10-15', 5, '大臂固定，只做肘伸'),
(2, 2, '拉(背二头)',   '硬拉',           '背',   '3',   '5-8',   1, '杠铃贴近小腿，先蹬地后展髋'),
(2, 2, '拉(背二头)',   '引体向上',       '背',   '3-4', '6-10',  2, '若无法完成可借助弹力带'),
(2, 2, '拉(背二头)',   '坐姿划船',       '背',   '3',   '8-12',  3, '肩胛后缩，避免耸肩'),
(2, 2, '拉(背二头)',   '面拉',           '肩',   '3',   '15-20', 4, '拉向面部，强化后束与肩袖'),
(2, 2, '拉(背二头)',   '杠铃弯举',       '手臂', '3',   '8-12',  5, '肘部固定，避免借力摆动'),
(2, 3, '腿(腿核心)',   '深蹲',           '腿',   '4',   '6-10',  1, '主项动作，全程保持核心刚性'),
(2, 3, '腿(腿核心)',   '腿举',           '腿',   '3',   '10-15', 2, '脚距略宽，刺激股四头内侧'),
(2, 3, '腿(腿核心)',   '保加利亚分腿蹲', '腿',   '3',   '8-12',  3, '后脚搭凳，重心放在前腿'),
(2, 3, '腿(腿核心)',   '坐姿腿弯举',     '腿',   '3',   '12-15', 4, '顶峰收缩1秒，控制离心'),
(2, 3, '腿(腿核心)',   '卷腹',           '核心', '3',   '15-20', 5, '靠腹肌卷起，不要用颈部发力'),
(2, 5, '推(胸肩三头)', '杠铃卧推',       '胸',   '4',   '6-10',  1, '主项动作，组间休息2-3分钟'),
(2, 5, '推(胸肩三头)', '上斜哑铃推举',   '胸',   '3',   '8-12',  2, '上胸发力，肘部约45度夹角'),
(2, 5, '推(胸肩三头)', '坐姿推举',       '肩',   '3-4', '8-12',  3, '背部贴紧靠垫，不要过度挺腰'),
(2, 5, '推(胸肩三头)', '侧平举',         '肩',   '3',   '12-15', 4, '小重量多次数，控制离心'),
(2, 5, '推(胸肩三头)', '绳索下压',       '手臂', '3',   '10-15', 5, '大臂固定，只做肘伸'),
(2, 6, '拉(背二头)',   '硬拉',           '背',   '3',   '5-8',   1, '杠铃贴近小腿，先蹬地后展髋'),
(2, 6, '拉(背二头)',   '引体向上',       '背',   '3-4', '6-10',  2, '若无法完成可借助弹力带'),
(2, 6, '拉(背二头)',   '坐姿划船',       '背',   '3',   '8-12',  3, '肩胛后缩，避免耸肩'),
(2, 6, '拉(背二头)',   '面拉',           '肩',   '3',   '15-20', 4, '拉向面部，强化后束与肩袖'),
(2, 6, '拉(背二头)',   '杠铃弯举',       '手臂', '3',   '8-12',  5, '肘部固定，避免借力摆动'),
(2, 7, '腿(腿核心)',   '深蹲',           '腿',   '4',   '6-10',  1, '主项动作，全程保持核心刚性'),
(2, 7, '腿(腿核心)',   '腿举',           '腿',   '3',   '10-15', 2, '脚距略宽，刺激股四头内侧'),
(2, 7, '腿(腿核心)',   '保加利亚分腿蹲', '腿',   '3',   '8-12',  3, '后脚搭凳，重心放在前腿'),
(2, 7, '腿(腿核心)',   '坐姿腿弯举',     '腿',   '3',   '12-15', 4, '顶峰收缩1秒，控制离心'),
(2, 7, '腿(腿核心)',   '卷腹',           '核心', '3',   '15-20', 5, '靠腹肌卷起，不要用颈部发力')
ON DUPLICATE KEY UPDATE
    day_label        = VALUES(day_label),
    target_muscle    = VALUES(target_muscle),
    recommended_sets = VALUES(recommended_sets),
    recommended_reps = VALUES(recommended_reps),
    sort_order       = VALUES(sort_order),
    notes            = VALUES(notes);

-- ---------- 模板3：五分化训练 ----------
INSERT INTO t_template_exercise
    (template_id, day_of_cycle, day_label, action_name, target_muscle, recommended_sets, recommended_reps, sort_order, notes) VALUES
(3, 1, '胸',   '杠铃卧推',       '胸',   '4',   '6-10',  1, '主项动作，逐步加重至工作重量'),
(3, 1, '胸',   '上斜杠铃卧推',   '胸',   '3-4', '8-12',  2, '上胸优先，斜板30-45度'),
(3, 1, '胸',   '哑铃飞鸟',       '胸',   '3',   '10-15', 3, '肘部微屈，靠胸肌内收'),
(3, 1, '胸',   '双杠臂屈伸',     '胸',   '3',   '8-12',  4, '身体前倾，重心在胸部'),
(3, 2, '背',   '引体向上',       '背',   '4',   '6-10',  1, '全程控制，肩胛先下沉后拉'),
(3, 2, '背',   '杠铃划船',       '背',   '4',   '8-12',  2, '躯干约45度，拉向腹部'),
(3, 2, '背',   '高位下拉',       '背',   '3',   '10-12', 3, '宽握，下拉至锁骨位置'),
(3, 2, '背',   '直臂下压',       '背',   '3',   '12-15', 4, '手臂伸直，靠背阔肌下压'),
(3, 3, '腿',   '深蹲',           '腿',   '4',   '6-10',  1, '主项动作，注意呼吸与核心'),
(3, 3, '腿',   '腿举',           '腿',   '4',   '10-15', 2, '脚踩位置偏低侧重股四头'),
(3, 3, '腿',   '罗马尼亚硬拉',   '腿',   '3',   '8-12',  3, '髋铰链，感受腘绳肌拉伸'),
(3, 3, '腿',   '坐姿腿屈伸',     '腿',   '3',   '12-15', 4, '顶峰收缩1秒，控制下放'),
(3, 4, '肩',   '站姿推举',       '肩',   '4',   '8-12',  1, '核心收紧，避免腰部代偿'),
(3, 4, '肩',   '侧平举',         '肩',   '4',   '12-15', 2, '中束主导，肘略高于腕'),
(3, 4, '肩',   '反向飞鸟',       '肩',   '3',   '12-15', 3, '俯身，后束发力，避免耸肩'),
(3, 4, '肩',   '耸肩',           '肩',   '3',   '10-15', 4, '直上直下，顶峰停顿1秒'),
(3, 5, '手臂', '杠铃弯举',       '手臂', '4',   '8-12',  1, '肘部固定，避免借力摆动'),
(3, 5, '手臂', '窄距卧推',       '手臂', '4',   '8-12',  2, '窄握，肘内收贴近体侧'),
(3, 5, '手臂', '锤式弯举',       '手臂', '3',   '10-12', 3, '对握，强化肱桡肌与肱肌'),
(3, 5, '手臂', '绳索下压',       '手臂', '3',   '12-15', 4, '大臂夹紧，只做肘伸'),
(3, 6, '核心', '卷腹',           '核心', '3-4', '15-20', 1, '靠腹肌卷起，避免颈部发力'),
(3, 6, '核心', '悬垂举腿',       '核心', '3',   '10-15', 2, '控制摆动，下腹主导'),
(3, 6, '核心', '俄罗斯转体',     '核心', '3',   '20-30', 3, '转体时保持躯干稳定'),
(3, 6, '核心', '山羊挺身',       '核心', '3',   '12-15', 4, '下背与臀腿协同，避免超伸')
ON DUPLICATE KEY UPDATE
    day_label        = VALUES(day_label),
    target_muscle    = VALUES(target_muscle),
    recommended_sets = VALUES(recommended_sets),
    recommended_reps = VALUES(recommended_reps),
    sort_order       = VALUES(sort_order),
    notes            = VALUES(notes);

