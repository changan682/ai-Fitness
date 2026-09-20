package com.fitness.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作名称 → 目标肌群 映射
 * <p>
 * <b>为什么需要它</b>：规范 7.1 要求 AI 训练总结做「与上次<b>同部位</b>训练对比」，
 * 但 {@code t_training_record} 只存了用户自由填写的 {@code action_name}，
 * 没有肌群字段。因此在 Java 侧用关键词规则把动作名归一到 6 大肌群，
 * 才能把「上周也练了胸」这件事算出来。
 * <p>
 * <b>规则顺序至关重要</b>：采用「先匹配到的规则生效」，且<b>特例必须排在泛化规则之前</b>。
 * 例如「反向飞鸟」练的是肩后束、「飞鸟」练的是胸 —— 若「飞鸟→胸」写在前面，
 * 反向飞鸟会被错误归到胸，进而把「肩部对比」算成「胸部对比」。
 * 同类冲突还有：双杠臂屈伸（胸）vs 仰卧臂屈伸（手臂）、
 * 罗马尼亚硬拉/直腿硬拉（腿）vs 硬拉（背）。
 * <p>
 * <b>匹配不到时返回 null 而不是猜</b>：宁可退化成「同名动作对比」，
 * 也不要把不确定的动作塞进某个肌群，产出错误的对比结论。
 */
public final class ActionMuscleMapper {

    /** 6 大肌群（与规范「目标肌群（胸/背/腿/肩/手臂/核心）」一致） */
    public static final String CHEST = "胸";
    public static final String BACK = "背";
    public static final String LEGS = "腿";
    public static final String SHOULDERS = "肩";
    public static final String ARMS = "手臂";
    public static final String CORE = "核心";

    /**
     * 关键词 → 肌群，<b>顺序即优先级</b>（LinkedHashMap 保证迭代顺序）
     * <p>
     * 每条都写明「为什么这么归」，避免日后有人随手调整顺序把特例埋掉。
     */
    private static final Map<String, String> RULES = new LinkedHashMap<>();

    static {
        // ---------- 第一优先：与泛化词冲突的特例 ----------
        RULES.put("反向飞鸟", SHOULDERS);   // 练肩后束，不是胸（必须先于「飞鸟」）
        RULES.put("俯身飞鸟", SHOULDERS);   // 同上
        RULES.put("面拉", SHOULDERS);       // 强化后束与肩袖
        RULES.put("侧平举", SHOULDERS);
        RULES.put("前平举", SHOULDERS);

        RULES.put("窄距卧推", ARMS);        // 握距窄→肱三头主导（必须先于「卧推」）
        RULES.put("仰卧臂屈伸", ARMS);
        RULES.put("颈后臂屈伸", ARMS);
        RULES.put("过顶臂屈伸", ARMS);
        RULES.put("绳索下压", ARMS);
        RULES.put("三头", ARMS);
        RULES.put("双杠臂屈伸", CHEST);     // 身体前倾→胸；放在「臂屈伸」泛化规则之前

        RULES.put("罗马尼亚硬拉", LEGS);    // 髋铰链以腘绳肌为主（必须先于「硬拉」）
        RULES.put("直腿硬拉", LEGS);
        RULES.put("单腿硬拉", LEGS);

        RULES.put("坐姿腿屈伸", LEGS);      // 必须先于「臂屈伸/腿屈伸」类泛化
        RULES.put("腿弯举", LEGS);
        RULES.put("腿屈伸", LEGS);
        RULES.put("腿举", LEGS);
        RULES.put("保加利亚", LEGS);
        RULES.put("弓步", LEGS);
        RULES.put("提踵", LEGS);           // 小腿也归腿
        RULES.put("臀推", LEGS);
        RULES.put("臀桥", LEGS);

        RULES.put("卷腹", CORE);
        RULES.put("仰卧起坐", CORE);
        RULES.put("平板支撑", CORE);
        RULES.put("悬垂举腿", CORE);
        RULES.put("举腿", CORE);
        RULES.put("俄罗斯转体", CORE);
        RULES.put("转体", CORE);
        RULES.put("山羊挺身", CORE);
        RULES.put("核心", CORE);

        RULES.put("锤式弯举", ARMS);
        RULES.put("杠铃弯举", ARMS);
        RULES.put("哑铃弯举", ARMS);
        RULES.put("牧师凳", ARMS);
        RULES.put("弯举", ARMS);
        RULES.put("腕弯举", ARMS);

        // ---------- 第二优先：常规动作 ----------
        RULES.put("卧推", CHEST);
        RULES.put("推胸", CHEST);
        RULES.put("夹胸", CHEST);
        RULES.put("飞鸟", CHEST);          // 特例已在上面拦掉
        RULES.put("俯卧撑", CHEST);
        RULES.put("硬拉", BACK);           // 特例已在上面拦掉
        RULES.put("引体", BACK);
        RULES.put("划船", BACK);
        RULES.put("下拉", BACK);
        RULES.put("直臂下压", BACK);
        RULES.put("深蹲", LEGS);
        RULES.put("腿", LEGS);
        RULES.put("推举", SHOULDERS);
        RULES.put("耸肩", SHOULDERS);
        RULES.put("肩", SHOULDERS);
        RULES.put("下压", ARMS);           // 绳索下压之外的各类下压
        RULES.put("臂屈伸", ARMS);
        RULES.put("背", BACK);
        RULES.put("胸", CHEST);
    }

    private ActionMuscleMapper() {
        // 工具类，禁止实例化
    }

    /**
     * 解析动作名对应的肌群
     *
     * @return 6 大肌群之一；无法判断时返回 {@code null}（调用方需处理该情况）
     */
    public static String resolve(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            return null;
        }
        // 统一小写并去掉空白：用户可能写 "Barbell 卧推" 或带多余空格
        String normalized = actionName.trim().toLowerCase().replaceAll("\\s+", "");
        for (Map.Entry<String, String> rule : RULES.entrySet()) {
            if (normalized.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return null;
    }

    /** 批量解析，忽略无法判断的动作 */
    public static Set<String> resolveAll(List<String> actionNames) {
        Set<String> muscles = new LinkedHashSet<>();
        if (actionNames == null) {
            return muscles;
        }
        for (String action : actionNames) {
            String muscle = resolve(action);
            if (muscle != null) {
                muscles.add(muscle);
            }
        }
        return muscles;
    }

    /**
     * 找出两组动作中<b>同肌群</b>的部分
     * <p>
     * 这是「与上次同部位对比」的核心：只要两次训练命中同一个肌群就算可比，
     * 不要求动作名一致（这正是「同部位」与「同名动作」的区别）。
     *
     * @return 交集肌群；为空表示两次训练没有共同部位
     */
    public static Set<String> sharedMuscles(List<String> todayActions, List<String> previousActions) {
        Set<String> today = resolveAll(todayActions);
        today.retainAll(resolveAll(previousActions));
        return today;
    }

    /** 判断给定的动作集合是否属于同一肌群（用于「本次是否只练了一个部位」这类判断） */
    public static List<String> unresolved(List<String> actionNames) {
        List<String> unknown = new ArrayList<>();
        if (actionNames == null) {
            return unknown;
        }
        for (String action : actionNames) {
            if (resolve(action) == null) {
                unknown.add(action);
            }
        }
        return unknown;
    }
}
