package com.fitness.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitness.cache.CacheKeys;
import com.fitness.cache.RedisCacheService;
import com.fitness.dto.ApplyScheduleRequest;
import com.fitness.dto.ApplyScheduleResponse;
import com.fitness.dto.ScheduleDayResponse;
import com.fitness.dto.TemplateExerciseResponse;
import com.fitness.dto.WorkoutTemplateResponse;
import com.fitness.entity.TemplateExercise;
import com.fitness.entity.UserWorkoutSchedule;
import com.fitness.entity.WorkoutPlanTemplate;
import com.fitness.exception.BusinessException;
import com.fitness.exception.ErrorCode;
import com.fitness.repository.TemplateExerciseRepository;
import com.fitness.repository.UserWorkoutScheduleRepository;
import com.fitness.repository.WorkoutPlanTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 训练计划模板服务 — 模板查看 + 套用模板生成一周训练安排
 * <p>
 * 缓存策略（提示词第五章 2.7）：模板数据变更频率极低，采用 Cache-Aside +
 * 24h 长 TTL，并在应用启动时由 CacheWarmUpRunner 主动预热。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkoutPlanService {

    /** 模板缓存基准 TTL（秒）= 24 小时，实际写入会叠加 ±300s 随机扰动 */
    private static final long TEMPLATE_CACHE_TTL_SECONDS = 24 * 3600L;

    private final WorkoutPlanTemplateRepository templateRepository;
    private final TemplateExerciseRepository exerciseRepository;
    private final UserWorkoutScheduleRepository scheduleRepository;
    private final RedisCacheService redisCacheService;
    private final ObjectMapper objectMapper;

    /**
     * 查询全部启用中的训练模板（含动作明细）— 提示词 6.1
     * <p>
     * 读取顺序：Redis → 未命中查 MySQL → 回写 Redis。
     * 缓存内容为一个 JSON 数组字符串，反序列化失败时降级回源 MySQL，不影响接口可用性。
     */
    public List<WorkoutTemplateResponse> listTemplates() {
        Object cached = redisCacheService.get(CacheKeys.WORKOUT_TEMPLATE_ALL);
        if (cached != null) {
            try {
                log.debug("训练模板缓存命中: key={}", CacheKeys.WORKOUT_TEMPLATE_ALL);
                return objectMapper.readValue(cached.toString(),
                        new TypeReference<List<WorkoutTemplateResponse>>() {});
            } catch (Exception e) {
                // 缓存脏数据不应导致接口失败，删除后回源
                log.warn("训练模板缓存反序列化失败，已删除并回源MySQL: {}", e.getMessage());
                redisCacheService.delete(CacheKeys.WORKOUT_TEMPLATE_ALL);
            }
        }

        List<WorkoutTemplateResponse> templates = loadTemplatesFromDb();
        try {
            redisCacheService.setWithJitter(CacheKeys.WORKOUT_TEMPLATE_ALL,
                    objectMapper.writeValueAsString(templates), TEMPLATE_CACHE_TTL_SECONDS);
        } catch (Exception e) {
            log.warn("训练模板写入缓存失败（不影响接口返回）: {}", e.getMessage());
        }
        return templates;
    }

    /** 从 MySQL 加载模板 + 明细（一次性批量取明细，避免 N+1 查询） */
    private List<WorkoutTemplateResponse> loadTemplatesFromDb() {
        List<WorkoutPlanTemplate> templates = templateRepository.findByIsActiveOrderByIdAsc(1);
        if (templates.isEmpty()) {
            return List.of();
        }

        List<Long> templateIds = templates.stream().map(WorkoutPlanTemplate::getId).toList();
        Map<Long, List<TemplateExercise>> exerciseMap = exerciseRepository
                .findByTemplateIdInOrderByDayOfCycleAscSortOrderAsc(templateIds)
                .stream()
                .collect(Collectors.groupingBy(TemplateExercise::getTemplateId,
                        LinkedHashMap::new, Collectors.toList()));

        return templates.stream()
                .map(t -> WorkoutTemplateResponse.builder()
                        .id(t.getId())
                        .templateName(t.getTemplateName())
                        .description(t.getDescription())
                        .splitType(t.getSplitType())
                        .targetLevel(t.getTargetLevel())
                        .exercises(exerciseMap.getOrDefault(t.getId(), List.of()).stream()
                                .map(this::toExerciseResponse)
                                .toList())
                        .build())
                .toList();
    }

    /**
     * 套用模板生成一周训练安排 — 提示词 6.2
     * <p>
     * 语义：模板的 day_of_cycle(1-7) 映射为 startDate + (dayOfCycle-1)；
     * 同一用户同一周重复套用时先删后建（覆盖），避免安排堆积。
     * 休息日在模板中没有明细行，因此不会出现在返回的 days 中。
     */
    @Transactional
    public ApplyScheduleResponse applyTemplate(Long userId, ApplyScheduleRequest req) {
        WorkoutPlanTemplate template = templateRepository.findById(req.getTemplateId())
                .filter(t -> t.getIsActive() != null && t.getIsActive() == 1)
                .orElseThrow(() -> new BusinessException(ErrorCode.TEMPLATE_NOT_FOUND));

        LocalDate startDate = LocalDate.parse(req.getStartDate());
        if (startDate.getDayOfWeek() != DayOfWeek.MONDAY) {
            // 规范约定前端传周一；非周一只提示，不擅自改写用户传入的起始日
            log.warn("套用模板的 startDate 非周一: userId={}, startDate={}", userId, startDate);
        }

        List<TemplateExercise> exercises =
                exerciseRepository.findByTemplateIdOrderByDayOfCycleAscSortOrderAsc(template.getId());
        if (exercises.isEmpty()) {
            log.warn("模板无动作明细: templateId={}", template.getId());
            return ApplyScheduleResponse.builder()
                    .scheduleId(null)
                    .weekStart(startDate.toString())
                    .days(List.of())
                    .build();
        }

        // 覆盖语义：清空该用户本周已有安排（唯一索引/事务保证不会出现半周数据）
        LocalDate weekEnd = startDate.plusDays(6);
        scheduleRepository.deleteByUserIdAndScheduleDateBetween(userId, startDate, weekEnd);

        // 按 dayOfCycle 分组（TreeMap 保证按天升序）
        Map<Integer, List<TemplateExercise>> byDay = exercises.stream()
                .collect(Collectors.groupingBy(TemplateExercise::getDayOfCycle,
                        TreeMap::new, Collectors.toList()));

        List<UserWorkoutSchedule> toSave = new ArrayList<>();
        List<ScheduleDayResponse> days = new ArrayList<>();

        for (Map.Entry<Integer, List<TemplateExercise>> entry : byDay.entrySet()) {
            LocalDate scheduleDate = startDate.plusDays(entry.getKey() - 1L);
            List<String> actions = new ArrayList<>();

            for (TemplateExercise ex : entry.getValue()) {
                toSave.add(UserWorkoutSchedule.builder()
                        .userId(userId)
                        .scheduleDate(scheduleDate)
                        .templateId(template.getId())
                        .actionName(ex.getActionName())
                        .targetMuscle(ex.getTargetMuscle())
                        .targetSets(parseLowerBound(ex.getRecommendedSets(), 3))
                        .targetReps(parseLowerBound(ex.getRecommendedReps(), 10))
                        .isCompleted(0)
                        .build());

                // 文案格式与规范示例一致：杠铃卧推 3-4组×8-12次
                actions.add(ex.getActionName() + " " + ex.getRecommendedSets()
                        + "组×" + ex.getRecommendedReps() + "次");
            }

            days.add(ScheduleDayResponse.builder()
                    .scheduleDate(scheduleDate.toString())
                    .dayLabel(entry.getValue().get(0).getDayLabel())
                    .actions(actions)
                    .build());
        }

        List<UserWorkoutSchedule> saved = scheduleRepository.saveAll(toSave);
        Long scheduleId = saved.isEmpty() ? null : saved.get(0).getId();

        log.info("套用训练模板完成: userId={}, template={}, weekStart={}, 训练日={}天, 动作={}条",
                userId, template.getTemplateName(), startDate, days.size(), saved.size());

        return ApplyScheduleResponse.builder()
                .scheduleId(scheduleId)
                .weekStart(startDate.toString())
                .days(days)
                .build();
    }

    /**
     * 解析推荐范围字符串的下界，如 "3-4" → 3，"8-12" → 8。
     * 解析失败时返回默认值，保证 t_user_workout_schedule 的目标组数/次数非空。
     */
    private int parseLowerBound(String range, int defaultValue) {
        if (range == null || range.isBlank()) {
            return defaultValue;
        }
        String first = range.split("[-~～]")[0].trim();
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException e) {
            log.warn("推荐范围解析失败，使用默认值: range={}, default={}", range, defaultValue);
            return defaultValue;
        }
    }

    private TemplateExerciseResponse toExerciseResponse(TemplateExercise ex) {
        return TemplateExerciseResponse.builder()
                .dayOfCycle(ex.getDayOfCycle())
                .dayLabel(ex.getDayLabel())
                .actionName(ex.getActionName())
                .targetMuscle(ex.getTargetMuscle())
                .recommendedSets(ex.getRecommendedSets())
                .recommendedReps(ex.getRecommendedReps())
                .notes(ex.getNotes())
                .build();
    }
}
