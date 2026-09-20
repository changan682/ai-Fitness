package com.fitness.repository;

import com.fitness.entity.TemplateExercise;
import com.fitness.entity.UserWorkoutSchedule;
import com.fitness.entity.WorkoutPlanTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository 层切片测试（@DataJpaTest + H2）
 * <p>
 * 覆盖训练计划模块三个 Repository：模板主表、模板动作明细、用户训练安排。
 * 明细/安排的查询都带「多级排序」，排序错误会直接导致前端分组错乱，因此这里逐条断言顺序。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Repository 测试：WorkoutPlanTemplate + TemplateExercise + UserWorkoutSchedule")
class WorkoutPlanRepositoryTest {

    private static final Long USER = 1001L;
    private static final Long OTHER_USER = 2002L;

    @Autowired
    private WorkoutPlanTemplateRepository templateRepository;

    @Autowired
    private TemplateExerciseRepository exerciseRepository;

    @Autowired
    private UserWorkoutScheduleRepository scheduleRepository;

    @BeforeEach
    void cleanUp() {
        scheduleRepository.deleteAll();
        exerciseRepository.deleteAll();
        templateRepository.deleteAll();
    }

    // ==================== WorkoutPlanTemplateRepository ====================

    @Test
    @DisplayName("findByIsActiveOrderByIdAsc：只返回启用模板，且按 id 升序（前端展示顺序稳定）")
    void findByIsActiveShouldExcludeDisabledAndSortById() {
        Long first = saveTemplate("三分化训练", "三分化", 1);
        Long disabled = saveTemplate("已下线模板", "五分化", 0);
        Long third = saveTemplate("推拉腿训练", "推拉腿", 1);
        templateRepository.flush();

        List<WorkoutPlanTemplate> active = templateRepository.findByIsActiveOrderByIdAsc(1);

        assertEquals(2, active.size(), "只能返回 is_active=1 的模板");
        assertEquals(List.of(first, third),
                active.stream().map(WorkoutPlanTemplate::getId).toList(),
                "必须按 id 升序，且排除 is_active=0");
        assertFalse(active.stream().anyMatch(t -> t.getId().equals(disabled)));

        assertEquals(1, templateRepository.findByIsActiveOrderByIdAsc(0).size(),
                "按 is_active=0 查询应只返回被禁用的模板");
    }

    @Test
    @DisplayName("findByTemplateName：模板名唯一索引对应的精确查询")
    void findByTemplateNameShouldReturnExactTemplate() {
        saveTemplate("三分化训练", "三分化", 1);
        saveTemplate("推拉腿训练", "推拉腿", 1);
        templateRepository.flush();

        Optional<WorkoutPlanTemplate> template = templateRepository.findByTemplateName("推拉腿训练");
        assertTrue(template.isPresent());
        assertEquals("推拉腿", template.get().getSplitType());

        assertTrue(templateRepository.findByTemplateName("不存在的模板").isEmpty());
    }

    @Test
    @DisplayName("findBySplitTypeOrderByIdAsc：按分化方式查询并按 id 升序")
    void findBySplitTypeShouldSortById() {
        Long a = saveTemplate("三分化A", "三分化", 1);
        saveTemplate("推拉腿", "推拉腿", 1);
        Long b = saveTemplate("三分化B", "三分化", 1);
        templateRepository.flush();

        assertEquals(List.of(a, b),
                templateRepository.findBySplitTypeOrderByIdAsc("三分化")
                        .stream().map(WorkoutPlanTemplate::getId).toList());
        assertTrue(templateRepository.findBySplitTypeOrderByIdAsc("全身").isEmpty());
    }

    // ==================== TemplateExerciseRepository ====================

    @Test
    @DisplayName("findByTemplateIdOrderByDayOfCycleAscSortOrderAsc：单模板明细按「第几天→排序号」升序")
    void findSingleTemplateExercisesShouldBeOrdered() {
        Long templateId = saveTemplate("三分化训练", "三分化", 1);
        // 故意乱序插入：同一天内 sortOrder 反着来，跨天也反着来
        saveExercise(templateId, 2, "背+二头", "引体向上", "背", 2);
        saveExercise(templateId, 1, "胸+三头", "杠铃卧推", "胸", 1);
        saveExercise(templateId, 2, "背+二头", "杠铃划船", "背", 1);
        saveExercise(templateId, 1, "胸+三头", "上斜哑铃卧推", "胸", 2);
        exerciseRepository.flush();

        List<TemplateExercise> exercises = exerciseRepository
                .findByTemplateIdOrderByDayOfCycleAscSortOrderAsc(templateId);

        assertEquals(List.of("杠铃卧推", "上斜哑铃卧推", "杠铃划船", "引体向上"),
                exercises.stream().map(TemplateExercise::getActionName).toList(),
                "必须先按 dayOfCycle 升序，同一天再按 sortOrder 升序：详情接口依赖此顺序直接分组");
    }

    @Test
    @DisplayName("findByTemplateIdInOrderByDayOfCycleAscSortOrderAsc：批量取多模板明细（避免 N+1）")
    void findExercisesOfMultipleTemplatesShouldBeOrderedPerTemplate() {
        Long templateA = saveTemplate("三分化训练", "三分化", 1);
        Long templateB = saveTemplate("推拉腿训练", "推拉腿", 1);
        Long unrelated = saveTemplate("五分化训练", "五分化", 1);

        saveExercise(templateA, 1, "胸+三头", "杠铃卧推", "胸", 1);
        saveExercise(templateA, 2, "背+二头", "引体向上", "背", 1);
        saveExercise(templateB, 1, "推", "站姿推举", "肩", 2);
        saveExercise(templateB, 1, "推", "哑铃卧推", "胸", 3);
        saveExercise(templateB, 3, "腿", "深蹲", "腿", 1);
        saveExercise(unrelated, 1, "腿", "腿举", "腿", 1);   // 未被请求的模板，必须被排除
        exerciseRepository.flush();

        List<TemplateExercise> exercises = exerciseRepository
                .findByTemplateIdInOrderByDayOfCycleAscSortOrderAsc(List.of(templateA, templateB));

        assertEquals(5, exercises.size(), "只返回被请求的两个模板的明细，不含无关模板（腿举 应被排除）");
        assertTrue(exercises.stream().allMatch(e ->
                        e.getTemplateId().equals(templateA) || e.getTemplateId().equals(templateB)),
                "结果集必须限定在传入的 templateIds 内");
        assertEquals(List.of("杠铃卧推", "站姿推举", "哑铃卧推", "引体向上", "深蹲"),
                exercises.stream().map(TemplateExercise::getActionName).toList(),
                "多模板批量查询仍是全局「dayOfCycle → sortOrder」升序："
                        + "day1(卧推/推举/哑铃卧推) → day2(引体向上) → day3(深蹲)");
    }

    @Test
    @DisplayName("findByTemplateIdIn...：空集合入参不得返回全表数据")
    void findExercisesWithEmptyIdsShouldReturnEmpty() {
        Long templateId = saveTemplate("三分化训练", "三分化", 1);
        saveExercise(templateId, 1, "胸+三头", "杠铃卧推", "胸", 1);
        exerciseRepository.flush();

        assertTrue(exerciseRepository
                        .findByTemplateIdInOrderByDayOfCycleAscSortOrderAsc(List.of()).isEmpty(),
                "IN () 必须返回空集：若退化成全表查询，模板列表接口会串数据");
    }

    // ==================== UserWorkoutScheduleRepository ====================

    @Test
    @DisplayName("findByUserIdAndScheduleDateBetweenOrderByScheduleDateAscIdAsc：区间升序，同日按 id 升序")
    void findScheduleByRangeShouldSortByDateThenId() {
        Long templateId = saveTemplate("三分化训练", "三分化", 1);
        // 同一天两条：先插入的 id 小，必须排在前面（保持模板内的动作顺序）
        scheduleRepository.save(schedule(USER, "2026-07-06", templateId, "杠铃卧推", 1));
        scheduleRepository.save(schedule(USER, "2026-07-06", templateId, "上斜哑铃卧推", 2));
        scheduleRepository.save(schedule(USER, "2026-07-07", templateId, "引体向上", 1));
        scheduleRepository.save(schedule(USER, "2026-07-13", templateId, "深蹲", 1));      // 区间外
        scheduleRepository.save(schedule(OTHER_USER, "2026-07-06", templateId, "硬拉", 1)); // 他人
        scheduleRepository.flush();

        List<UserWorkoutSchedule> week = scheduleRepository
                .findByUserIdAndScheduleDateBetweenOrderByScheduleDateAscIdAsc(
                        USER, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-12"));

        assertEquals(List.of("杠铃卧推", "上斜哑铃卧推", "引体向上"),
                week.stream().map(UserWorkoutSchedule::getActionName).toList(),
                "先按日期升序，同一天按 id 升序，且不含他人与区间外数据");
    }

    @Test
    @DisplayName("findByUserIdAndScheduleDateOrderByIdAsc：查某天安排")
    void findScheduleByDateShouldReturnThatDay() {
        Long templateId = saveTemplate("三分化训练", "三分化", 1);
        scheduleRepository.save(schedule(USER, "2026-07-06", templateId, "杠铃卧推", 1));
        scheduleRepository.save(schedule(USER, "2026-07-06", templateId, "上斜哑铃卧推", 2));
        scheduleRepository.save(schedule(USER, "2026-07-07", templateId, "引体向上", 1));
        scheduleRepository.flush();

        List<UserWorkoutSchedule> day = scheduleRepository
                .findByUserIdAndScheduleDateOrderByIdAsc(USER, LocalDate.parse("2026-07-06"));

        assertEquals(2, day.size());
        assertEquals(1, day.get(0).getTargetSets().intValue());
        assertEquals(2, day.get(1).getTargetSets().intValue());
    }

    @Test
    @DisplayName("deleteByUserIdAndScheduleDateBetween：套用模板的「覆盖」语义，只清当前用户当前周")
    void deleteByRangeShouldOnlyRemoveOwnCurrentWeek() {
        Long templateId = saveTemplate("三分化训练", "三分化", 1);
        scheduleRepository.save(schedule(USER, "2026-07-06", templateId, "杠铃卧推", 1));
        scheduleRepository.save(schedule(USER, "2026-07-07", templateId, "引体向上", 1));
        scheduleRepository.save(schedule(USER, "2026-07-13", templateId, "深蹲", 1));       // 下一周，必须保留
        scheduleRepository.save(schedule(OTHER_USER, "2026-07-06", templateId, "硬拉", 1)); // 他人，必须保留
        scheduleRepository.flush();

        scheduleRepository.deleteByUserIdAndScheduleDateBetween(
                USER, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-12"));
        scheduleRepository.flush();

        assertTrue(scheduleRepository
                        .findByUserIdAndScheduleDateBetweenOrderByScheduleDateAscIdAsc(
                                USER, LocalDate.parse("2026-07-06"), LocalDate.parse("2026-07-12"))
                        .isEmpty(),
                "本周安排应被清空（重复套用模板时先删后建）");
        assertEquals(1, scheduleRepository
                        .findByUserIdAndScheduleDateOrderByIdAsc(USER, LocalDate.parse("2026-07-13")).size(),
                "下一周的安排不能被误删");
        assertEquals(1, scheduleRepository
                        .findByUserIdAndScheduleDateOrderByIdAsc(OTHER_USER, LocalDate.parse("2026-07-06")).size(),
                "其他用户的安排不能被误删");
    }

    // ==================== 测试数据 ====================

    private Long saveTemplate(String name, String splitType, int isActive) {
        return templateRepository.save(WorkoutPlanTemplate.builder()
                .templateName(name)
                .splitType(splitType)
                .isActive(isActive)
                .build()).getId();
    }

    private void saveExercise(Long templateId, int dayOfCycle, String dayLabel,
                              String actionName, String targetMuscle, int sortOrder) {
        exerciseRepository.save(TemplateExercise.builder()
                .templateId(templateId)
                .dayOfCycle(dayOfCycle)
                .dayLabel(dayLabel)
                .actionName(actionName)
                .targetMuscle(targetMuscle)
                .sortOrder(sortOrder)
                .build());
    }

    private UserWorkoutSchedule schedule(Long userId, String date, Long templateId,
                                         String actionName, int targetSets) {
        return UserWorkoutSchedule.builder()
                .userId(userId)
                .scheduleDate(LocalDate.parse(date))
                .templateId(templateId)
                .actionName(actionName)
                .targetMuscle("胸")
                .targetSets(targetSets)
                .targetReps(10)
                .isCompleted(0)
                .build();
    }
}
