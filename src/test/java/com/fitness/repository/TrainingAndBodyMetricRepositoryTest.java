package com.fitness.repository;

import com.fitness.entity.BodyMetric;
import com.fitness.entity.TrainingRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository 层切片测试（@DataJpaTest + H2）
 * <p>
 * 覆盖 TrainingRecordRepository（分页派生查询 + 4 个 {@code @Query}，含原生递归 CTE）
 * 与 BodyMetricRepository（4 个派生查询）。测试库为 H2 内存库，不触碰开发库。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
// 测试专用方言：绕过主代码「Double + @Column(scale=1)」导致的元数据构建异常（见报告「发现的主代码问题」）。
// 注意：原先这里还要挂 H2MySqlStatementInspector + h2-mysql-compat.sql 去给 MySQL 原生递归 CTE 打补丁；
// 「连续训练天数」改为「JPQL 取日期 + Java 算逻辑」后已是可移植写法，H2 直接能跑，那套补丁已删除。
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=com.fitness.testsupport.LenientH2Dialect"
})
@DisplayName("Repository 测试：TrainingRecordRepository + BodyMetricRepository")
class TrainingAndBodyMetricRepositoryTest {

    private static final Long USER = 1001L;
    private static final Long OTHER_USER = 2002L;

    @Autowired
    private TrainingRecordRepository trainingRecordRepository;

    @Autowired
    private BodyMetricRepository bodyMetricRepository;

    @BeforeEach
    void cleanUp() {
        trainingRecordRepository.deleteAll();
        bodyMetricRepository.deleteAll();
    }

    // ==================== TrainingRecordRepository ====================

    @Test
    @DisplayName("findByUserIdAndTrainingDateBetween：按用户隔离 + 区间闭区间 + 分页 + 日期倒序")
    void findByUserIdAndTrainingDateBetweenShouldFilterByUserRangeAndPage() {
        // 用户 1001：07-01 ~ 07-04 各一条；用户 2002：同区间一条（必须被隔离掉）
        saveTraining(USER, "2026-07-01", "深蹲", 4, 10, "80.0");
        saveTraining(USER, "2026-07-02", "深蹲", 4, 10, "85.0");
        saveTraining(USER, "2026-07-03", "深蹲", 4, 10, "90.0");
        saveTraining(USER, "2026-07-04", "深蹲", 4, 10, "95.0");
        saveTraining(USER, "2026-07-11", "深蹲", 4, 10, "100.0");   // 区间外
        saveTraining(OTHER_USER, "2026-07-02", "深蹲", 4, 10, "70.0");
        trainingRecordRepository.flush();

        Page<TrainingRecord> page1 = trainingRecordRepository.findByUserIdAndTrainingDateBetween(
                USER, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-04"),
                PageRequest.of(0, 2));

        assertEquals(4, page1.getTotalElements(), "total 应为该用户区间内 4 条（不含他人 1 条、不含区间外 1 条）");
        assertEquals(2, page1.getContent().size());
        assertTrue(page1.getContent().stream().allMatch(r -> USER.equals(r.getUserId())),
                "必须按 userId 隔离，不能返回他人记录");

        // 起始日不传可比较字段，这里用「首页 2 条 + 次页 2 条 → 合计 4 条且无重叠」验证分页正确
        Page<TrainingRecord> page2 = trainingRecordRepository.findByUserIdAndTrainingDateBetween(
                USER, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-04"),
                PageRequest.of(1, 2));
        assertEquals(2, page2.getContent().size());
        List<Long> allIds = List.of(
                page1.getContent().get(0).getId(), page1.getContent().get(1).getId(),
                page2.getContent().get(0).getId(), page2.getContent().get(1).getId());
        assertEquals(4, allIds.stream().distinct().count(), "两页数据不得重叠");
    }

    @Test
    @DisplayName("findByUserIdAndTrainingDateBetween（不分页）：用于统计与导出")
    void findByUserIdAndTrainingDateBetweenListShouldReturnAllInRange() {
        saveTraining(USER, "2026-07-01", "硬拉", 3, 8, "100.0");
        saveTraining(USER, "2026-07-08", "硬拉", 3, 8, "110.0");
        saveTraining(USER, "2026-07-20", "硬拉", 3, 8, "120.0");
        trainingRecordRepository.flush();

        List<TrainingRecord> list = trainingRecordRepository.findByUserIdAndTrainingDateBetween(
                USER, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-10"));

        assertEquals(2, list.size(), "闭区间 [07-01, 07-10] 应含 2 条");
    }

    @Test
    @DisplayName("findByUserIdAndActionNameContaining：动作名模糊匹配 + 区间过滤 + 日期倒序")
    void findByUserIdAndActionNameContainingShouldFuzzyMatchAndSortDesc() {
        saveTraining(USER, "2026-07-01", "杠铃卧推", 4, 10, "60.0");
        saveTraining(USER, "2026-07-05", "上斜卧推", 4, 12, "50.0");
        saveTraining(USER, "2026-07-06", "深蹲", 4, 10, "80.0");        // 动作不匹配
        saveTraining(USER, "2026-07-20", "杠铃卧推", 4, 10, "65.0");    // 区间外
        trainingRecordRepository.flush();

        Page<TrainingRecord> page = trainingRecordRepository.findByUserIdAndActionNameContaining(
                USER, "卧推", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-10"),
                PageRequest.of(0, 10));

        assertEquals(2, page.getTotalElements(), "「卧推」应命中 2 条（不含深蹲、不含区间外）");
        assertEquals("上斜卧推", page.getContent().get(0).getActionName(), "应按训练日期倒序，07-05 在前");
        assertEquals("杠铃卧推", page.getContent().get(1).getActionName());
    }

    @Test
    @DisplayName("findByUserIdAndTrainingDate：查某用户某天的全部记录（今日训练接口回源用）")
    void findByUserIdAndTrainingDateShouldReturnThatDayOnly() {
        saveTraining(USER, "2026-07-30", "卧推", 4, 10, "60.0");
        saveTraining(USER, "2026-07-30", "划船", 4, 10, "50.0");
        saveTraining(USER, "2026-07-31", "深蹲", 4, 10, "80.0");
        saveTraining(OTHER_USER, "2026-07-30", "卧推", 4, 10, "60.0");
        trainingRecordRepository.flush();

        List<TrainingRecord> today = trainingRecordRepository.findByUserIdAndTrainingDate(
                USER, LocalDate.parse("2026-07-30"));

        assertEquals(2, today.size());
        assertTrue(today.stream().allMatch(r -> USER.equals(r.getUserId())));
    }

    @Test
    @DisplayName("findMaxWeightByUserIdAndActionName：取历史最大重量，无记录返回空 Optional")
    void findMaxWeightShouldReturnHistoricalMax() {
        saveTraining(USER, "2026-07-01", "杠铃卧推", 4, 10, "60.0");
        saveTraining(USER, "2026-07-08", "杠铃卧推", 4, 8, "67.5");
        saveTraining(USER, "2026-07-15", "杠铃卧推", 4, 6, "65.0");
        saveTraining(USER, "2026-07-15", "深蹲", 4, 6, "120.0");   // 同名用户不同动作
        trainingRecordRepository.flush();

        Optional<BigDecimal> max = trainingRecordRepository
                .findMaxWeightByUserIdAndActionName(USER, "杠铃卧推");
        assertTrue(max.isPresent());
        assertEquals(0, max.get().compareTo(new BigDecimal("67.5")), "最大重量应为 67.5");

        assertTrue(trainingRecordRepository
                        .findMaxWeightByUserIdAndActionName(USER, "不存在的动作").isEmpty(),
                "无记录的动作应返回空 Optional（Service 层据此回退为 0）");
    }

    @Test
    @DisplayName("findMaxVolumeByUserIdAndActionName：取历史最大容量")
    void findMaxVolumeShouldReturnHistoricalMax() {
        saveTraining(USER, "2026-07-01", "杠铃卧推", 4, 10, "60.0");   // 2400
        saveTraining(USER, "2026-07-08", "杠铃卧推", 3, 15, "55.0");   // 2475
        trainingRecordRepository.flush();

        Optional<BigDecimal> max = trainingRecordRepository
                .findMaxVolumeByUserIdAndActionName(USER, "杠铃卧推");
        assertTrue(max.isPresent());
        assertEquals(0, max.get().compareTo(new BigDecimal("2475.0")), "最大容量应为 3×15×55=2475.0");
    }

    @Test
    @DisplayName("findDistinctUserIdsByTrainingDate：返回指定日期训练过的去重用户ID（每日提醒用）")
    void findDistinctUserIdsByTrainingDateShouldDeduplicate() {
        saveTraining(USER, "2026-07-30", "卧推", 4, 10, "60.0");
        saveTraining(USER, "2026-07-30", "划船", 4, 10, "50.0");        // 同一用户第二条
        saveTraining(OTHER_USER, "2026-07-30", "深蹲", 4, 10, "80.0");
        saveTraining(USER, "2026-07-29", "深蹲", 4, 10, "80.0");        // 前一天
        trainingRecordRepository.flush();

        List<Long> userIds = trainingRecordRepository
                .findDistinctUserIdsByTrainingDate(LocalDate.parse("2026-07-30"));

        assertEquals(2, userIds.size(), "同一用户当天两条记录只能出现一次");
        assertTrue(userIds.containsAll(List.of(USER, OTHER_USER)));
    }

    @Test
    @DisplayName("findDistinctTrainingDates：按用户隔离 + 日期去重倒序（连续训练天数的取数入口）")
    void findDistinctTrainingDatesShouldDedupeAndSortDesc() {
        // 07-01/02/03 连续，07-04 断档，07-05 同一天两条记录（必须去重成 1 天）
        saveTraining(USER, "2026-07-01", "深蹲", 4, 10, "80.0");
        saveTraining(USER, "2026-07-02", "深蹲", 4, 10, "80.0");
        saveTraining(USER, "2026-07-03", "深蹲", 4, 10, "80.0");
        saveTraining(USER, "2026-07-05", "深蹲", 4, 10, "80.0");
        saveTraining(USER, "2026-07-05", "卧推", 4, 10, "60.0");
        saveTraining(OTHER_USER, "2026-07-04", "深蹲", 4, 10, "80.0");  // 他人记录不得出现
        trainingRecordRepository.flush();

        List<LocalDate> dates = trainingRecordRepository.findDistinctTrainingDates(
                USER, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-05"));

        assertEquals(List.of(
                        LocalDate.parse("2026-07-05"),
                        LocalDate.parse("2026-07-03"),
                        LocalDate.parse("2026-07-02"),
                        LocalDate.parse("2026-07-01")),
                dates,
                "同一用户同一天多条记录只能出现一次，且按日期倒序；他人记录必须被隔离");

        assertEquals(List.of(), trainingRecordRepository.findDistinctTrainingDates(
                        USER, LocalDate.parse("2026-07-10"), LocalDate.parse("2026-07-20")),
                "区间内无记录时返回空列表");
    }

    // ==================== BodyMetricRepository ====================

    @Test
    @DisplayName("findByUserIdAndRecordDate：同日唯一记录查询（重复录入校验）")
    void findByUserIdAndRecordDateShouldReturnSameDayRecord() {
        saveMetric(USER, "2026-07-30", "70.0");
        saveMetric(OTHER_USER, "2026-07-30", "80.0");
        bodyMetricRepository.flush();

        Optional<BodyMetric> metric = bodyMetricRepository
                .findByUserIdAndRecordDate(USER, LocalDate.parse("2026-07-30"));
        assertTrue(metric.isPresent());
        assertEquals(0, metric.get().getWeightKg().compareTo(new BigDecimal("70.0")),
                "必须按 userId 隔离，不能取到他人同一天的数据");

        assertTrue(bodyMetricRepository
                        .findByUserIdAndRecordDate(USER, LocalDate.parse("2026-07-29")).isEmpty(),
                "该用户当天无记录应返回空（Service 据此允许新增）");
    }

    @Test
    @DisplayName("findTopByUserIdOrderByRecordDateDesc：取最新一条体测数据")
    void findTopShouldReturnLatestRecord() {
        saveMetric(USER, "2026-07-01", "72.0");
        saveMetric(USER, "2026-07-20", "69.5");
        saveMetric(USER, "2026-07-10", "71.0");
        saveMetric(OTHER_USER, "2026-07-31", "88.0");   // 他人更新，不得影响
        bodyMetricRepository.flush();

        Optional<BodyMetric> latest = bodyMetricRepository.findTopByUserIdOrderByRecordDateDesc(USER);
        assertTrue(latest.isPresent());
        assertEquals("2026-07-20", latest.get().getRecordDate().toString(), "应取记录日期最新的一条");
        assertEquals(0, latest.get().getWeightKg().compareTo(new BigDecimal("69.5")));
    }

    @Test
    @DisplayName("findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc：取区间基准记录（7日变化计算）")
    void findFirstBeforeOrEqualShouldReturnNearestEarlierRecord() {
        saveMetric(USER, "2026-07-01", "72.0");
        saveMetric(USER, "2026-07-20", "70.0");
        saveMetric(USER, "2026-07-30", "69.0");
        bodyMetricRepository.flush();

        // 基准日 = 07-30 - 7天 = 07-23 → 不晚于 07-23 的最近一条是 07-20
        Optional<BodyMetric> baseline = bodyMetricRepository
                .findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                        USER, LocalDate.parse("2026-07-23"));
        assertTrue(baseline.isPresent());
        assertEquals("2026-07-20", baseline.get().getRecordDate().toString());

        // 边界：正好等于基准日时必须包含当天（LessThanEqual 而非 LessThan）
        assertEquals("2026-07-20", bodyMetricRepository
                        .findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                                USER, LocalDate.parse("2026-07-20"))
                .orElseThrow().getRecordDate().toString(),
                "LessThanEqual：基准日当天有记录时必须返回当天那条");

        assertTrue(bodyMetricRepository
                        .findFirstByUserIdAndRecordDateLessThanEqualOrderByRecordDateDesc(
                                USER, LocalDate.parse("2026-06-30")).isEmpty(),
                "基准日早于全部记录时应返回空（Service 据此不下发体重变化）");
    }

    @Test
    @DisplayName("findByUserIdAndRecordDateBetweenOrderByRecordDateAsc：按日期升序返回区间数据（滑动平均输入）")
    void findByDateRangeShouldReturnAscendingOrder() {
        // 故意乱序插入，验证排序由查询保证而不是靠插入顺序
        saveMetric(USER, "2026-07-03", "71.0");
        saveMetric(USER, "2026-07-01", "70.0");
        saveMetric(USER, "2026-07-02", "70.5");
        saveMetric(USER, "2026-07-05", "72.0");    // 区间外
        bodyMetricRepository.flush();

        List<BodyMetric> trend = bodyMetricRepository
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                        USER, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-03"));

        assertEquals(3, trend.size());
        assertEquals(List.of("2026-07-01", "2026-07-02", "2026-07-03"),
                trend.stream().map(m -> m.getRecordDate().toString()).toList(),
                "滑动平均算法依赖严格升序，查询必须保证排序");
        assertFalse(trend.isEmpty());
    }

    // ==================== 测试数据 ====================

    private void saveTraining(Long userId, String date, String action,
                              int sets, int reps, String weight) {
        trainingRecordRepository.save(TrainingRecord.builder()
                .userId(userId)
                .trainingDate(LocalDate.parse(date))
                .actionName(action)
                .sets(sets)
                .reps(reps)
                .weightKg(new BigDecimal(weight))
                .build());   // volume 由 @PrePersist 自动计算，这里顺带验证容量落库
    }

    private void saveMetric(Long userId, String date, String weight) {
        bodyMetricRepository.save(BodyMetric.builder()
                .userId(userId)
                .recordDate(LocalDate.parse(date))
                .weightKg(new BigDecimal(weight))
                .build());
    }
}
