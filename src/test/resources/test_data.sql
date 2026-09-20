-- ==================== 测试数据（Java 层） ====================
-- 规范「测试策略 / 测试数据规范」要求：测试数据统一用 test_data.sql 管理，
-- 使用内存库（H2）或独立测试库，禁止污染开发/生产数据库。
--
-- 用法（H2，配合 @DataJpaTest / @SpringBootTest）：
--   @Sql(scripts = "/test_data.sql")
--
-- 注意：
--   * 本脚本使用 H2 方言的 DATEADD（配合 application-test.yml 的 MODE=MySQL）。
--     若要导入真实 MySQL 测试库，请把 DATEADD('DAY', -1, CURRENT_DATE)
--     改写为 DATE_ADD(CURRENT_DATE, INTERVAL -1 DAY)。
--   * 不写 t_food_library —— 30 种食物由 sql/init.sql 的种子数据提供，避免两处维护。
--   * 日期使用 CURRENT_DATE 相对偏移，保证「今天/昨天/上周」这类断言与执行日期无关。
--   * 需要「登录成功」链路的用例，请勿直接依赖本文件的 password 值：
--     请在测试里用 BCrypt.withDefaults().hashToString(12, "Abc@Test2026".toCharArray())
--     现场生成哈希再落库，避免硬编码哈希与真实口令不一致造成误判。

-- 清理（幂等，便于反复导入）
DELETE FROM t_training_record WHERE user_id IN (1001, 1002);
DELETE FROM t_body_metric     WHERE user_id IN (1001, 1002);
DELETE FROM t_diet_record     WHERE user_id IN (1001, 1002);
DELETE FROM t_user            WHERE id IN (1001, 1002);

-- ---------- 用户 ----------
-- password 为占位哈希（非 "Abc@Test2026" 的真实 BCrypt 值），仅供不需要登录的查询类用例使用
INSERT INTO t_user (id, nickname, gender, birth_date, height, weight,
                    training_goal, training_level, injury_record, phone, password) VALUES
(1001, '健身达人', 1, '1995-06-15', 175.0, 70.5, '增肌', '进阶', '["左肩旧伤"]',  '13800138000',
 '$2a$12$e0N0Jj0hqXk7FqYq0hQZ4uJ0FqXk7FqYq0hQZ4uJ0FqXk7FqYq0hQZ'),
(1002, '减脂小白', 2, '1998-03-02', 163.0, 58.0, '减脂', '新手', '[]',            '13900139000',
 '$2a$12$e0N0Jj0hqXk7FqYq0hQZ4uJ0FqXk7FqYq0hQZ4uJ0FqXk7FqYq0hQZ');

-- ---------- 训练记录（user 1001，覆盖「今天 / 昨天 / 上周」三种查询场景）----------
-- 容量 volume = sets × reps × weight_kg，与生产逻辑保持一致
INSERT INTO t_training_record (user_id, training_date, action_name, sets, reps, weight_kg,
                               duration_min, rpe, volume, remark) VALUES
(1001, CURRENT_DATE,                  '杠铃卧推',     4, 10, 60.0, 45, 8, 2400.0, '最后一组力竭'),
(1001, CURRENT_DATE,                  '上斜哑铃卧推', 3, 12, 25.0, 45, 7,  900.0, NULL),
(1001, CURRENT_DATE,                  '绳索夹胸',     3, 15, 15.0, 45, 6,  675.0, NULL),
(1001, DATEADD('DAY', -1, CURRENT_DATE), '深蹲',      4,  8, 80.0, 50, 9, 2560.0, '状态好'),
(1001, DATEADD('DAY', -2, CURRENT_DATE), '引体向上',  4,  8,  0.0, 30, 8,    0.0, '自重'),
(1002, CURRENT_DATE,                  '快走',         1, 30,  0.0, 30, 4,    0.0, '有氧');

-- ---------- 身体数据（user 1001，含 7 日滑动平均的窗口数据）----------
INSERT INTO t_body_metric (user_id, record_date, weight_kg, waist_cm, arm_cm, leg_cm, body_fat_pct) VALUES
(1001, DATEADD('DAY', -6, CURRENT_DATE), 71.5, 81.0, 35.8, 54.5, 19.0),
(1001, DATEADD('DAY', -3, CURRENT_DATE), 71.0, 80.5, 35.9, 54.8, 18.8),
(1001, CURRENT_DATE,                     70.5, 80.0, 36.0, 55.0, 18.5);

-- ---------- 饮食记录（user 1001，热量按食物库 133/116/246 kcal 换算）----------
INSERT INTO t_diet_record (user_id, record_date, meal_type, food_name, weight_g, calories_kcal) VALUES
(1001, CURRENT_DATE, '早餐', '燕麦片',   100, 377.0),
(1001, CURRENT_DATE, '午餐', '鸡胸肉',   200, 266.0),
(1001, CURRENT_DATE, '午餐', '米饭(熟)', 150, 174.0),
(1001, CURRENT_DATE, '加餐', '香蕉',     120, 111.6);
