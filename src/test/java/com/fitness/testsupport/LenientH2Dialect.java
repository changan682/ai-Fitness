package com.fitness.testsupport;

import org.hibernate.dialect.Dialect.SizeStrategy;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.JdbcType;

/**
 * 测试专用 H2 方言 —— 容忍主代码里「浮点类型 + scale」的无意义注解组合。
 * <p>
 * <b>为什么需要它</b>（主代码缺陷，详见最终报告「发现的主代码问题」#2）：
 * {@code User.height / User.weight} 声明为 {@code Double}，却同时标了
 * {@code @Column(precision = 5, scale = 1)}。Hibernate 6 在<b>构建元数据</b>阶段
 * （{@code MetadataBuildingProcess → BasicValue.resolve → Column.calculateColumnSize}）
 * 就会调用方言的 {@code SizeStrategy#resolveSize}，默认实现遇到
 * 「浮点类型 + scale &gt; 0」直接抛：
 * <pre>IllegalArgumentException: scale has no meaning for SQL floating point types</pre>
 * 该异常与 ddl-auto 无关，发生在 EntityManagerFactory 创建时，
 * 因此 H2 环境下任何 {@code @DataJpaTest} / {@code @SpringBootTest} 都起不来。
 * （生产用的 {@code MySQLDialect} 自带宽松的 SizeStrategy，所以问题只在规范指定的
 * H2 测试库上暴露；但实体注解与 {@code sql/init.sql} 的 {@code DECIMAL(5,1)} 依然不一致。）
 * <p>
 * <b>做法</b>：保持 H2Dialect 的全部行为不变，只在 {@code resolveSize} 抛出该异常时
 * 降级为「不指定长度/精度」，让 Hibernate 使用该方言对 DOUBLE 的默认映射。
 * 按任务约束不修改 src/main，故只能在此以测试侧方言绕过。
 * 主代码修复后本类应删除（与该类绑定的 {@code hibernate.dialect} 测试属性一并移除）。
 */
public class LenientH2Dialect extends H2Dialect {

    /** 原始策略：除浮点 scale 外的一切行为都委托给它，避免改变其它列的 DDL/长度推导 */
    private final SizeStrategy delegate = super.getSizeStrategy();

    private final SizeStrategy lenient = (JdbcType jdbcType, JavaType<?> javaType,
                                          Integer precision, Integer scale, Long length) -> {
        // 仅在「浮点类型 + 非 0 scale」这一种组合上把 scale 归零后重试，
        // 其余情况与原策略完全一致（返回的 Size 与「没写 precision/scale 的 Double 列」等价）。
        if (isFloatingPoint(jdbcType) && scale != null && scale != 0) {
            return delegate.resolveSize(jdbcType, javaType, precision, 0, length);
        }
        return delegate.resolveSize(jdbcType, javaType, precision, scale, length);
    };

    @Override
    public SizeStrategy getSizeStrategy() {
        return lenient;
    }

    /** SQL 浮点类型判定 —— 与 Hibernate Dialect$SizeStrategyImpl 抛异常的分支保持一致 */
    private static boolean isFloatingPoint(JdbcType jdbcType) {
        return switch (jdbcType.getDdlTypeCode()) {
            case java.sql.Types.FLOAT, java.sql.Types.REAL, java.sql.Types.DOUBLE -> true;
            default -> false;
        };
    }
}
