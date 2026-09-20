package com.fitness.repository;

import com.fitness.entity.TemplateExercise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 模板动作明细 Repository
 */
@Repository
public interface TemplateExerciseRepository extends JpaRepository<TemplateExercise, Long> {

    /**
     * 查单个模板的全部动作，按「周期第几天 → 排序号」升序。
     * 详情接口依赖此顺序直接分组，无需在 Service 再排序。
     */
    List<TemplateExercise> findByTemplateIdOrderByDayOfCycleAscSortOrderAsc(Long templateId);

    /**
     * 批量查多个模板的动作 — 用于模板列表接口一次性取回全部明细，避免 N+1 查询。
     */
    List<TemplateExercise> findByTemplateIdInOrderByDayOfCycleAscSortOrderAsc(Collection<Long> templateIds);
}
