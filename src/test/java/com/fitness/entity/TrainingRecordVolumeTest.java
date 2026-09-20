package com.fitness.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 训练容量自动计算单元测试（提示词测试策略：TrainingRecordService 容量计算）
 * <p>
 * 容量 = sets × reps × weightKg，在 @PrePersist / @PreUpdate 生命周期回调中计算。
 * 测试类与实体同包，可直接访问 protected 的 onCreate / onUpdate。
 */
class TrainingRecordVolumeTest {

    @Test
    void shouldAutoCalculateVolumeOnCreate() {
        TrainingRecord record = TrainingRecord.builder()
                .sets(4).reps(10).weightKg(new BigDecimal("60.0"))
                .build();

        record.onCreate();

        assertEquals(0, record.getVolume().compareTo(new BigDecimal("2400.0")),
                "容量应为 4×10×60 = 2400.0");
    }

    @Test
    void shouldAutoCalculateVolumeWithDecimalWeight() {
        TrainingRecord record = TrainingRecord.builder()
                .sets(3).reps(12).weightKg(new BigDecimal("22.5"))
                .build();

        record.onCreate();

        assertEquals(0, record.getVolume().compareTo(new BigDecimal("810.0")),
                "容量应为 3×12×22.5 = 810.0");
    }

    @Test
    void shouldRecalculateVolumeOnUpdate() {
        TrainingRecord record = TrainingRecord.builder()
                .sets(4).reps(10).weightKg(new BigDecimal("60.0"))
                .build();
        record.onCreate();

        // 模拟修改重量后触发 @PreUpdate
        record.setWeightKg(new BigDecimal("65.0"));
        record.onUpdate();

        assertEquals(0, record.getVolume().compareTo(new BigDecimal("2600.0")),
                "更新后容量应重新计算为 4×10×65 = 2600.0");
    }

    @Test
    void shouldNotCalculateWhenSetsMissing() {
        TrainingRecord record = TrainingRecord.builder()
                .reps(10).weightKg(new BigDecimal("60.0"))
                .build();

        record.onCreate();

        assertEquals(null, record.getVolume(), "缺少组数时不计算容量");
    }
}
