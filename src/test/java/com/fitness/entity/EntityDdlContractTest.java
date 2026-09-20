package com.fitness.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体 ↔ {@code sql/init.sql} 的列名 / 唯一索引契约测试（纯单元测试，不起 Spring 上下文）
 *
 * <h3>为什么必须有这个测试</h3>
 * 本项目 {@code application.yml} 配的是 {@code ddl-auto: none}：表结构由 {@code sql/init.sql}
 * 手工维护，Hibernate <b>从不校验</b>实体与 DDL 是否一致。而测试用的 H2 是
 * {@code create-drop}（见 {@code src/test/resources/application-test.yml}），表由实体
 * <b>反向推导</b>出来 —— 「实体写成什么样，测试库就是什么样」。
 * <p>
 * 两者叠加造成致命盲区：<b>实体列名与 init.sql 不一致时全部测试照常全绿，
 * 而线上每个查询都抛 MySQL 1054 {@code Unknown column}</b>。这不是假设，项目真实踩过：
 * <ul>
 *   <li>{@code FoodLibrary.caloriesPer100g} 的隐式物理名是 {@code calories_per100g}，
 *       而 DDL 里是 {@code calories_per_100g}。原因：Spring Boot 默认的
 *       {@link CamelCaseToUnderscoresNamingStrategy} 只在「小写→大写→小写」处插下划线，
 *       <b>数字不构成词边界</b> → 食物库与饮食记录两个模块全线不可用；</li>
 *   <li>{@code DietRecord.weightG} 的隐式名是 {@code weightg}（<b>该策略从不检查最后一个字符</b>），
 *       而 DDL 里是 {@code weight_g}。</li>
 * </ul>
 * 现场表现都只是启动日志里一条 WARN（{@code FoodLibraryService.warmUpCache} 把异常吞了），
 * 应用照常启动、健康检查照常 UP、203 项测试照常全绿，因此极难发现。
 *
 * <h3>这个测试怎么工作</h3>
 * 对每个实体字段算出「Hibernate 实际会用的物理列名」：显式 {@code @Column(name)} 优先，
 * 否则用<b>真实的</b> {@link CamelCaseToUnderscoresNamingStrategy} 推导 —— 与运行时同一套逻辑，
 * 而不是照着文档重写一份近似实现。再与 {@code init.sql} 解析出的列名逐表比对。
 * <p>
 * 实体清单由<b>扫描 {@code src/main/java/com/fitness/entity} 目录</b>得到，
 * 因此新增实体不会漏测；新增 DTO 之类非实体类会被 {@code @Entity} 判断排除。
 *
 * <h3>测试前提（由 {@link #physicalNamingStrategyMustNotBeOverridden()} 守卫）</h3>
 * 本测试假定应用使用默认物理命名策略。若将来在 yml 里自定义了
 * {@code spring.jpa.hibernate.naming.physical-strategy}，那个守卫测试会失败并提示同步本测试。
 */
@DisplayName("实体 ↔ sql/init.sql 列名/唯一索引契约（防 ddl-auto:none 下的静默错配）")
class EntityDdlContractTest {

    /** 与运行时同源的物理命名策略（Spring Boot 3.x 默认即此类） */
    private static final CamelCaseToUnderscoresNamingStrategy NAMING = new CamelCaseToUnderscoresNamingStrategy();

    /** 代码显式依赖其存在的唯一索引：表名 → 索引名 */
    private static final Map<String, String> REQUIRED_UNIQUE_KEYS = Map.of(
            "t_user", "uk_phone",
            "t_body_metric", "uk_user_date",
            "t_food_library", "uk_food_name",
            "t_weekly_plan", "uk_task_id",
            "t_ai_summary_cache", "uk_user_date");

    private static Map<String, Set<String>> ddlTables;
    private static List<Class<?>> entities;

    @BeforeAll
    static void loadFixtures() throws Exception {
        ddlTables = parseInitSql();
        entities = scanEntityClasses();
    }

    @Test
    @DisplayName("每个实体的物理列名都必须存在于 init.sql 的建表语句中")
    void everyMappedColumnMustExistInDdl() {
        // 先证明「解析器真的读懂了 DDL」与「实体真的被扫到了」，否则本测试可能空跑通过
        assertTrue(ddlTables.size() >= 10,
                "init.sql 应解析出至少 10 张表，实际 " + ddlTables.size() + " 张；解析逻辑可能失效");
        assertTrue(ddlTables.getOrDefault("t_food_library", Set.of()).contains("calories_per_100g"),
                "init.sql 解析结果应含 t_food_library.calories_per_100g，否则说明解析器没读懂 DDL");
        assertTrue(entities.size() >= 10,
                "应扫描到至少 10 个 @Entity 类，实际 " + entities.size() + " 个；扫描逻辑可能失效");

        List<String> problems = new ArrayList<>();
        int checkedColumns = 0;

        for (Class<?> entity : entities) {
            String table = physicalTableName(entity);
            Set<String> ddlColumns = ddlTables.get(table);
            if (ddlColumns == null) {
                problems.add("实体 " + entity.getSimpleName() + " 映射到表 " + table
                        + "，但 init.sql 里没有这张表");
                continue;
            }

            Set<String> mapped = physicalColumnNames(entity);
            assertFalse(mapped.isEmpty(),
                    "从 " + entity.getSimpleName() + " 取不到任何列，测试自身失效");
            checkedColumns += mapped.size();

            for (String column : mapped) {
                if (!ddlColumns.contains(column)) {
                    problems.add(entity.getSimpleName() + " → 表 " + table + " 的列 `" + column
                            + "` 在 init.sql 中不存在。请在实体字段上显式写 @Column(name = \"...\")。"
                            + "DDL 现有列：" + new TreeSet<>(ddlColumns));
                }
            }
        }

        assertTrue(checkedColumns >= 80,
                "总共只校验了 " + checkedColumns + " 个列，数量异常偏低，测试可能没真正跑起来");

        assertTrue(problems.isEmpty(),
                "发现 " + problems.size() + " 处实体与 init.sql 的列名不一致；ddl-auto:none 下"
                        + "这些列会让运行期 SQL 直接抛 MySQL 1054 Unknown column：\n  - "
                        + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("代码依赖的唯一索引必须真实存在于 init.sql（幂等兜底靠它们）")
    void requiredUniqueIndexesMustExistInDdl() {
        String sql;
        try {
            sql = readProjectFile("sql/init.sql").toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new AssertionError("找不到 sql/init.sql：" + e.getMessage(), e);
        }

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> required : REQUIRED_UNIQUE_KEYS.entrySet()) {
            String table = required.getKey();
            String index = required.getValue();
            String block = tableBlock(sql, table);
            if (block == null) {
                missing.add(table + "（整张表都没找到）");
                continue;
            }
            // 必须同时出现 UNIQUE KEY，避免把同名的普通 INDEX 误判为唯一索引
            if (!block.contains("unique key") || !block.contains(index)) {
                missing.add(table + " 缺唯一索引 " + index);
            }
        }

        assertTrue(missing.isEmpty(),
                "以下唯一索引在 init.sql 中不存在，而代码把它们当作幂等/防重的最终兜底：\n  - "
                        + String.join("\n  - ", missing));
    }

    /**
     * 守卫本测试的前提：应用必须使用默认物理命名策略
     * <p>
     * 本测试是用默认策略推算列名的，一旦 yml 自定义了物理命名策略，
     * 推算结果就不再等于运行时的真实列名 —— 那时必须同步修改本测试，而不是让它悄悄失去保护力。
     */
    @Test
    @DisplayName("前提守卫：不得自定义物理命名策略（否则本测试的推算不再等价于运行时）")
    void physicalNamingStrategyMustNotBeOverridden() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String file : List.of("src/main/resources/application.yml",
                "src/main/resources/application-docker.yml",
                "src/test/resources/application-test.yml")) {
            String yml = readProjectFile(file);
            if (yml.contains("physical-strategy") || yml.contains("physical_strategy")) {
                offenders.add(file);
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下配置文件自定义了物理命名策略，本测试的列名推算将不再等价于运行时，"
                        + "请同步修改 EntityDdlContractTest：\n  - " + String.join("\n  - ", offenders));
    }

    // ==================== 物理名推算（与运行时同源） ====================

    private static String physicalTableName(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        String logical = (table != null && !table.name().isBlank())
                ? table.name()
                : entity.getSimpleName();
        return NAMING.toPhysicalTableName(Identifier.toIdentifier(logical), null).getText();
    }

    /**
     * 算出一个实体映射的全部物理列名
     * <p>
     * 规则与 Hibernate 绑定基本类型字段时一致：{@code @Column(name)} 显式指定优先，
     * 否则对字段名套用物理命名策略。实体里没有关联映射 / {@code @Embedded} / {@code @Enumerated}
     * （由本测试的兄弟断言与项目约定保证），故基本字段规则即可覆盖全部列。
     */
    private static Set<String> physicalColumnNames(Class<?> entity) {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : entity.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
                    || field.isAnnotationPresent(Transient.class)) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            if (column != null && !column.name().isBlank()) {
                columns.add(column.name().toLowerCase(Locale.ROOT));
                continue;
            }
            String physical = NAMING
                    .toPhysicalColumnName(Identifier.toIdentifier(field.getName()), null)
                    .getText()
                    .toLowerCase(Locale.ROOT);
            columns.add(physical);

            // 标识字段同时也是一条普通列，顺带确认它没被漏掉
            if (field.isAnnotationPresent(Id.class) && !columns.contains(physical)) {
                columns.add(physical);
            }
        }
        return columns;
    }

    // ==================== 实体扫描 ====================

    private static List<Class<?>> scanEntityClasses() throws Exception {
        Path dir = findProjectPath("src/main/java/com/fitness/entity");
        List<Class<?>> found = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) {
                    continue;
                }
                String className = "com.fitness.entity." + name.substring(0, name.length() - ".java".length());
                Class<?> candidate = Class.forName(className);
                if (candidate.isAnnotationPresent(Entity.class)) {
                    found.add(candidate);
                }
            }
        }
        found.sort(Comparator.comparing(Class::getSimpleName));
        return found;
    }

    // ==================== init.sql 解析 ====================

    /** 解析 init.sql：表名（小写）→ 列名集合（小写） */
    private static Map<String, Set<String>> parseInitSql() throws IOException {
        String sql = readProjectFile("sql/init.sql");
        Map<String, Set<String>> tables = new LinkedHashMap<>();

        Matcher tableMatcher = Pattern
                .compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\(",
                        Pattern.CASE_INSENSITIVE)
                .matcher(sql);

        while (tableMatcher.find()) {
            String tableName = tableMatcher.group(1).toLowerCase(Locale.ROOT);
            int start = tableMatcher.end();
            int end = sql.indexOf("\n) ENGINE", start);
            tables.put(tableName, parseColumns(sql.substring(start, end < 0 ? sql.length() : end)));
        }
        return tables;
    }

    /**
     * 从建表语句的列定义区解析列名
     * <p>
     * 只取「以标识符开头、后面跟类型」的行；显式跳过唯一键 / 索引 / 主键 / 外键 / 约束等表级定义，
     * 否则 {@code uk_phone}、{@code PRIMARY} 之类会被误当成列名。
     */
    private static Set<String> parseColumns(String body) {
        Set<String> columns = new LinkedHashSet<>();
        Set<String> tableLevelKeywords = Set.of(
                "unique", "index", "key", "primary", "foreign", "constraint",
                "fulltext", "spatial", "check", "engine", "default", "comment");

        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            // 去掉行尾的 COMMENT '...'，避免注释里的词干扰解析
            int commentAt = line.indexOf("COMMENT");
            if (commentAt > 0) {
                line = line.substring(0, commentAt).trim();
            }

            Matcher matcher = Pattern.compile("^`?(\\w+)`?\\s+[A-Za-z]").matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!tableLevelKeywords.contains(name)) {
                columns.add(name);
            }
        }
        return columns;
    }

    /** 取某张表 CREATE TABLE ( ... 之间的原文（传入的 sql 需已转小写），找不到返回 null */
    private static String tableBlock(String lowercasedSql, String tableName) {
        int at = lowercasedSql.indexOf("create table if not exists " + tableName);
        if (at < 0) {
            at = lowercasedSql.indexOf("create table " + tableName);
        }
        if (at < 0) {
            return null;
        }
        int end = lowercasedSql.indexOf("\n) engine", at);
        return end < 0 ? lowercasedSql.substring(at) : lowercasedSql.substring(at, end);
    }

    // ==================== 文件读取 ====================

    private static String readProjectFile(String relativePath) throws IOException {
        return Files.readString(findProjectPath(relativePath), StandardCharsets.UTF_8);
    }

    /**
     * 从当前工作目录逐级向上探测项目内文件
     * <p>
     * Maven surefire 的工作目录是项目根目录，但从 IDE 或别处运行时可能不同，
     * 向上探测可避免「换个方式跑就报文件找不到」。
     */
    private static Path findProjectPath(String relativePath) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            Path candidate = dir.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IOException("找不到 " + relativePath + "（从 "
                + Paths.get("").toAbsolutePath() + " 向上探测了 5 级）");
    }
}
