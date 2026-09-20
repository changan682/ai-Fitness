package com.fitness.repository;

import com.fitness.entity.WorkoutPlanTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 训练计划模板 Repository
 */
@Repository
public interface WorkoutPlanTemplateRepository extends JpaRepository<WorkoutPlanTemplate, Long> {

    /** 查询所有启用中的模板（按ID升序，保证前端展示顺序稳定） */
    List<WorkoutPlanTemplate> findByIsActiveOrderByIdAsc(Integer isActive);

    /** 按模板名称精确查询（模板名有 uk_template_name 唯一索引） */
    Optional<WorkoutPlanTemplate> findByTemplateName(String templateName);

    /** 按分化方式查询 */
    List<WorkoutPlanTemplate> findBySplitTypeOrderByIdAsc(String splitType);
}
