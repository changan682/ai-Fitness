"""核心 AI Agent 函数。

**第 6 周状态：三个同步 AI 能力已接真实模型**，仅 ``MOCK_MODE=true`` 时才退回本地模拟：

- :func:`generate_summary`  → 真实 LLM（DeepSeek）+ ``SUMMARY_SYSTEM_PROMPT``
- :func:`recommend_actions` → 真实 LLM + ``RECOMMEND_SYSTEM_PROMPT``，
  **带动作名白名单校验**，LLM 失败或越界时回退到内置动作库规则引擎
- :func:`evaluate_pose`     → 真实多模态（通义千问 VL）+ ``POSE_VL_SYSTEM_PROMPT``
- :func:`chat_with_rag`     → Embedding → Milvus Top-5 → LLM + ``RAG_SYSTEM_PROMPT``
- :func:`get_knowledge_health` → 真实 Milvus 连接 / Collection 状态

**降级策略（规范第十一章第 7 条）**：
- 动作推荐：LLM 不可用 → 回退规则引擎（动作名仍可枚举，接口不会失败）
- 姿态评估：**不编造分数**。多模态不可用就抛错，由 Java 返回规范规定的兜底文案
  「姿态评估服务暂时不可用，请稍后再试」；只有 ``MOCK_MODE=true`` 才走本地模拟打分
- 知识库问答：三层降级（完整 RAG → 纯 LLM → 内置知识库）
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import logging
import re
import time
from typing import Any, Dict, List, Optional, Set, Tuple

from .config import settings
from .models import (
    ActionRecommendation,
    ChatResponse,
    ChatSource,
    HealthData,
    KnowledgeEntry,
    KnowledgeHealthResponse,
    PoseEvaluateResponse,
    RecommendResponse,
    SummaryResponse,
)
from .utils import (
    AgentInputError,
    extract_json_object,
    first_sentences,
    fmt_num,
    now_iso,
    now_local,
    round_to_plate,
    to_float,
    to_int,
    today_str,
    truncate,
)

logger = logging.getLogger(__name__)


# ============================================================
# Prompt 工程模板（规范要求「必须内置于 agent.py 中，不得省略」）
# 第 4 周不调用 LLM，但模板先落地，第 5-6 周直接使用。
# 注意：RECOMMEND_SYSTEM_PROMPT 里 JSON 示例的 {{ }} 是规范原文写法
# （配合 str.format() 的转义）。第 5-6 周若要直接拼进 messages，
# 请用 RECOMMEND_SYSTEM_PROMPT.format() 或把 {{ }} 改成 { }。
# ============================================================

SUMMARY_SYSTEM_PROMPT = """你是一位拥有10年经验的资深健身教练，擅长根据训练数据给出专业、鼓励性的总结。

## 你的任务
根据用户的今日训练记录和上次对比数据，生成一段100-150字的训练总结。

## 输出格式（Markdown）
### 🏋️ 今日训练总结
**训练概览**：一句话概括今日训练量和状态
**⭐ 最佳表现**：指出表现最好的1个动作，说明原因（容量高/重量突破/动作质量好）
**📈 对比分析**：与上次同部位训练对比，用具体数字说明进步或退步
**💡 改进建议**：1条具体可执行的建议（不过度，聚焦最关键的1点）

## 风格要求
- 语气：专业但亲切，像教练和朋友对话
- 数据：引用具体数字增加说服力
- 鼓励：即使数据下降也要给出建设性建议而非批评
- 简洁：不超过150字，去掉冗余修饰词
- 使用Emoji提升可读性但不过度"""

SUMMARY_USER_PROMPT = """用户今日训练数据：
{records_text}

历史对比数据：
{comparison_text}

请生成今日训练总结。"""

RECOMMEND_SYSTEM_PROMPT = """你是一位精通运动解剖学的力量训练专家。

## 任务
根据目标肌群和可用器械，推荐5个训练动作。

## 输出格式（严格JSON）
{{
  "recommendations": [
    {{
      "actionName": "动作名称",
      "targetMuscle": "目标肌群",
      "focusArea": "更具体的发力区域",
      "recommendedSets": "推荐组数范围（如3-4组）",
      "recommendedReps": "推荐次数范围（如8-12次）",
      "difficulty": "新手/进阶/高级",
      "notes": "1-2句动作要点或注意事项",
      "equipment": ["所需器械"]
    }}
  ]
}}

## 推荐原则
- 优先推荐复合动作（多关节），再补充孤立动作（单关节）
- 动作难度需覆盖：2个适合新手 + 2个进阶 + 1个高级（可选）
- 注意动作多样性，避免重复刺激同一角度"""

RECOMMEND_USER_PROMPT = """目标肌群：{muscle}
可用器械：{equipment}

## 候选动作库（**只能从这里选**）
{candidates}

请从候选动作库中挑选 {count} 个动作，按推荐优先级排序（最该先练的排最前）。"""

POSE_VL_SYSTEM_PROMPT = """你是一位持有 NSCA-CSCS 认证的力量训练教练，擅长通过照片判断动作姿态问题。

## 你的任务
观察用户上传的 {action} 动作照片，给出客观、可执行的姿态评估。

## 评分标准（0-100）
- 90-100：脊柱中立、关节对位良好、动作幅度达标，没有明显问题
- 70-89：整体正确，但有 1-2 处可优化细节
- 50-69：存在明显姿态问题（如弓背、膝盖内扣、重心偏移），有受伤风险
- 0-49：存在高危姿态，必须立即纠正

## 硬性要求
1. 只依据照片中**真实可见**的内容判断。照片模糊、角度不对或与所报动作不符时，
   在 issues 里如实说明「照片无法判断」，**不要编造细节**。
2. issues 与 suggestions 必须一一对应且等长：每条问题都给出对应的纠正方法。
3. 建议要具体可执行（如「下蹲时主动向外打开膝盖，使膝盖与脚尖同向」），
   不要写「注意姿势」这类空话。
4. good_points 至少 1 条，且必须是照片里真实存在的优点。
5. 用中文，语气专业但不打击用户。

## 输出格式（严格 JSON，不要任何多余文字、不要 markdown 代码块）
{{"score": 78, "issues": ["..."], "suggestions": ["..."], "good_points": ["..."]}}"""

POSE_VL_USER_PROMPT = """动作名称：{action}

请评估照片中该动作的姿态，按系统提示的要求**只返回 JSON**。"""

RAG_SYSTEM_PROMPT = """你是一位博学的健身顾问，基于知识库提供科学、客观的回答。

## 回答规则
1. 优先基于下方【参考资料】回答，引用时标注来源
2. 如果参考资料不足以回答，明确告知"以下回答基于我的专业知识，仅供参考"
3. 涉及到个人健康、伤病问题时，必须加上"建议咨询专业医生或教练"
4. 回答结构：简短结论 → 详细解释（分点） → 总结建议

## 输出格式（Markdown）
- 使用 ## 标题、**加粗**、分点列表
- 引用来源格式：> 📚 参考：《来源名称》
- 字数：150-400字（视问题复杂度）

【参考资料】
{context}"""

RAG_USER_PROMPT = """用户问题：{question}

请基于以上参考资料回答。如果资料不足，请诚实说明并给出你最好的建议。"""

# 第 7 周 RabbitMQ 消费者使用（本周只落地模板）
WEEKLY_PLAN_SYSTEM_PROMPT = """你是一位负责学员长期训练规划的资深教练。

## 任务
基于用户本周的训练数据、饮食数据和体重变化，生成下周训练调整建议。

## 输出结构（Markdown）
### 📊 本周数据复盘
- 训练概况（天数/容量/RPE）
- 体重变化趋势分析
- 饮食热量分析

### 🎯 下周训练调整建议
1-3条具体调整（如：增加某动作强度、调整训练分化方式、增加有氧）

### ⚠️ 风险提示
识别过度训练信号（连续高RPE+体重下降+训练频率过高时提示减载）

## 风格
教练口吻，数据驱动，建议具体可执行，200-300字"""

WEEKLY_PLAN_USER_PROMPT = """本周数据（JSON）：
{weekly_data}

请生成下周训练建议。"""


# ============================================================
# 一、训练总结
# ============================================================

#: 从动作名称推断所属肌群（用于「今日完成 3 个胸肩动作」这类概览文案）
#: 顺序敏感：越长越具体的词放前面（如「窄距卧推」必须先于「卧推」）
ACTION_MUSCLE_RULES: List[Tuple[str, str]] = [
    ("卷腹", "核心"), ("平板", "核心"), ("举腿", "核心"), ("转体", "核心"),
    ("死虫", "核心"), ("健腹轮", "核心"), ("农夫行走", "核心"),
    ("窄距卧推", "手臂"), ("直臂下压", "背"), ("弯举", "手臂"), ("下压", "手臂"),
    ("臂屈伸", "手臂"),
    ("反向飞鸟", "肩"), ("面拉", "肩"), ("推肩", "肩"), ("推举", "肩"),
    ("侧平举", "肩"), ("前平举", "肩"), ("阿诺德", "肩"), ("直立划船", "肩"),
    ("深蹲", "腿"), ("腿举", "腿"), ("腿屈伸", "腿"), ("腿弯举", "腿"),
    ("箭步蹲", "腿"), ("分腿蹲", "腿"), ("臀桥", "腿"), ("提踵", "腿"),
    ("引体", "背"), ("下拉", "背"), ("划船", "背"), ("硬拉", "背"),
    ("卧推", "胸"), ("飞鸟", "胸"), ("夹胸", "胸"), ("俯卧撑", "胸"),
    ("推胸", "胸"), ("蝴蝶机", "胸"),
]

#: 动作技术要点提示（写进「改进建议」）
TECHNIQUE_CUES: List[Tuple[str, str]] = [
    ("卧推", "卧推时注意**肩胛骨全程收紧**、胸椎微挺，避免肩部代偿与前束过度参与"),
    ("深蹲", "深蹲全程保持核心收紧、膝盖与脚尖同向，离心阶段控制 2 秒不要「砸下去」"),
    ("硬拉", "硬拉起始位先「锁背再发力」，杠铃贴紧小腿走垂直轨迹，避免腰部先动"),
    ("划船", "划船时保持躯干稳定，先沉肩再用背阔肌带动肘部后拉，避免耸肩借力"),
    ("推举", "推举时收紧臀部与核心避免腰椎超伸，杠铃走贴近面部的垂直轨迹"),
    ("弯举", "弯举时固定大臂避免摆动借力，离心阶段放慢到 2-3 秒"),
    ("引体", "引体/下拉先沉肩再收缩背阔肌，不要用手臂蛮力硬拉"),
    ("下拉", "引体/下拉先沉肩再收缩背阔肌，不要用手臂蛮力硬拉"),
    ("腿举", "腿部器械训练保持膝盖与脚尖同向，不要锁死膝关节"),
    ("箭步", "箭步蹲保持躯干直立，前脚跟发力、后膝轻触地面即可"),
]


def _normalize_records(records: List[dict]) -> List[Dict[str, Any]]:
    """把 Java 传来的 records 规范化，并计算每条动作的容量。

    容量公式与 Java 侧完全一致（``TrainingRecord.volume``）：**sets × reps × weight**。
    容错：weight 缺失/非法按 0 处理（自重动作），并兼容 camelCase / 同义字段名。
    """
    parsed: List[Dict[str, Any]] = []
    for raw in records or []:
        if not isinstance(raw, dict):
            continue
        action = str(
            raw.get("action")
            or raw.get("action_name")
            or raw.get("actionName")
            or raw.get("name")
            or "未命名动作"
        ).strip()
        sets = to_int(raw.get("sets"))
        reps = to_int(raw.get("reps"))
        weight = to_float(
            raw.get("weight", raw.get("weight_kg", raw.get("weightKg"))), 0.0
        )
        rpe = to_int(raw.get("rpe"))
        duration = to_int(
            raw.get(
                "duration_minutes",
                raw.get("durationMinutes", raw.get("duration", 0)),
            )
        )
        parsed.append(
            {
                "action": action,
                "sets": sets,
                "reps": reps,
                "weight": weight,
                "rpe": rpe,
                "duration": duration,
                "volume": float(sets * reps * weight),
            }
        )
    return parsed


def _infer_muscles(parsed: List[Dict[str, Any]]) -> List[str]:
    """从动作名称推断涉及的肌群（去重、保持出现顺序）。"""
    muscles: List[str] = []
    for row in parsed:
        for keyword, muscle in ACTION_MUSCLE_RULES:
            if keyword in row["action"]:
                if muscle not in muscles:
                    muscles.append(muscle)
                break
    return muscles


def _technique_cue(action: str) -> Optional[str]:
    """按动作名给一条技术提示。"""
    for keyword, cue in TECHNIQUE_CUES:
        if keyword in action:
            return cue
    return None


def _intensity_phrase(max_rpe: int) -> str:
    if max_rpe >= 9:
        return "整体强度很高，已接近力竭"
    if max_rpe == 8:
        return "强度适中偏高"
    if max_rpe == 7:
        return "强度适中"
    if max_rpe >= 1:
        return "强度偏轻松，仍有提升空间"
    return "本次未记录 RPE"


def _praise(rpe: int) -> str:
    if rpe >= 9:
        return "强度拉满，意志力出色 💪"
    if rpe == 8:
        return "状态出色！"
    if rpe == 7:
        return "状态稳定"
    if rpe >= 1:
        return "完成质量稳定，还有加量空间"
    return "完成度良好"


def _opt_float(value: Any) -> Optional[float]:
    """可空浮点解析：None/非法 → None（用于区分「没传」和「传了 0」）。"""
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _parse_comparison(comparison: Optional[dict]) -> Dict[str, Any]:
    """解析 Java 传来的「上次对比数据」。

    兼容多种键名（Java ``PySummaryRequest.comparison`` 约定用 camelCase：
    ``previousDate`` / ``scope`` / ``sharedMuscles`` / ``sharedActions`` /
    ``records`` / ``volumeChangePct``，同时也接受 snake_case）：

    - ``records``：上次的训练记录，结构同 records，用于逐动作对比与「上次全天总容量」；
    - ``volumeChangePct``：Java 已算好的容量变化百分比 —— **单位是 %（5.3 表示 +5.3%）**；
    - ``scope``：**该百分比的统计口径**，取值 ``同部位`` / ``同名动作``。
      第 5 周起 Java 会把动作归一到 6 大肌群，优先按「同部位」对比；
      肌群判断不出来时才退化为「同名动作」。**渲染文案必须跟着 scope 走**，
      否则会出现「数字是总容量变化、文字却说同名动作」这种对不上的情况（第 4 周踩过）；
    - ``sharedMuscles``：``scope=同部位`` 时参与对比的肌群；
    - ``sharedActions``：参与对比的同名动作列表；
    - ``actions``：``{"动作名": 容量}`` 形式的上次容量明细。
    """
    parsed: Dict[str, Any] = {
        "date": None,
        "total_volume": None,
        "pct": None,
        "scope": None,
        "shared_muscles": [],
        "shared_actions": [],
        "by_action": {},
    }
    if not isinstance(comparison, dict) or not comparison:
        return parsed

    parsed["date"] = (
        comparison.get("previousDate")
        or comparison.get("previous_date")
        or comparison.get("date")
    )
    parsed["scope"] = comparison.get("scope")

    by_action: Dict[str, float] = {}
    prev_records = (
        comparison.get("records")
        or comparison.get("previous_records")
        or comparison.get("previousRecords")
        or []
    )
    if isinstance(prev_records, list):
        for row in _normalize_records([r for r in prev_records if isinstance(r, dict)]):
            by_action[row["action"]] = by_action.get(row["action"], 0.0) + row["volume"]

    actions_map = comparison.get("actions")
    if isinstance(actions_map, dict) and not by_action:
        for name, volume in actions_map.items():
            value = _opt_float(volume)
            if value is not None:
                by_action[str(name)] = value

    total = _opt_float(
        comparison.get(
            "totalVolume",
            comparison.get(
                "total_volume",
                comparison.get(
                    "previousVolume", comparison.get("previous_volume")
                ),
            ),
        )
    )
    if total is None and by_action:
        total = sum(by_action.values())

    # 过滤掉容量为 0 的项，避免「较上次提升 0%」这类无意义文案
    parsed["by_action"] = {k: v for k, v in by_action.items() if v > 0}
    parsed["total_volume"] = total
    parsed["pct"] = _opt_float(
        comparison.get(
            "volumeChangePct", comparison.get("volume_change_pct")
        )
    )
    shared = comparison.get("sharedActions") or comparison.get("shared_actions") or []
    parsed["shared_actions"] = [str(name) for name in shared] if isinstance(shared, list) else []
    muscles = comparison.get("sharedMuscles") or comparison.get("shared_muscles") or []
    parsed["shared_muscles"] = [str(m) for m in muscles] if isinstance(muscles, list) else []
    return parsed


def _comparison_bullets(
    parsed: List[Dict[str, Any]],
    total_volume: float,
    prev: Dict[str, Any],
    best: Dict[str, Any],
) -> List[str]:
    """生成「📈 对比分析」段落（至少 1 条，最多 5 条）。

    ⚠️ 三种「对比口径」必须区分开，否则文字与数字对不上：
    - ``scope=同部位``（规范 7.1 要求的口径）：``volumeChangePct`` 统计的是
      **共同肌群**的容量变化 —— 文案要写「共同肌群（胸）容量较上次」；
    - ``scope=同名动作``（肌群判断不出时的退化口径）：统计的是**同名动作**的容量变化；
    - 由 ``records`` 算出的 ``prev_total`` 是上次**全天总容量**，只有用它算出的百分比
      才能说「全天总容量较上次」。

    第 4 周曾把「同名动作」口径的百分比渲染成「同名动作」但数字取的是总容量，
    导致文字与数字矛盾 —— 所以这里严格按 ``scope`` 决定文案。
    """
    bullets: List[str] = []
    prev_total = prev.get("total_volume")
    prev_date = prev.get("date") or "上次训练"
    java_pct = prev.get("pct")
    shared: List[str] = prev.get("shared_actions") or []
    muscles: List[str] = prev.get("shared_muscles") or []
    scope = prev.get("scope")

    # 按 scope 生成「对比对象」的描述文字
    if scope == "同部位" and muscles:
        scope_label = f"共同肌群（{'、'.join(muscles[:3])}）"
    elif scope == "同名动作":
        scope_label = "同名动作"
    else:
        # Java 未给 scope（老版本或手工构造的请求）：不猜口径，用中性措辞
        scope_label = "可比动作"

    # ① Java 已算好的容量对比
    if java_pct is not None:
        if abs(java_pct) < 0.05:
            bullets.append(
                f"- {scope_label}容量与上次（{prev_date}）基本持平，"
                "可通过增加 1 组或小幅加重来打破平台"
            )
        else:
            trend = "提升" if java_pct >= 0 else "下降"
            advice = (
                "保持当前节奏，注意不要为了追容量牺牲动作质量"
                if java_pct >= 0
                else "属于正常波动（睡眠/饮食/恢复都会影响），先稳住动作质量与训练频率"
            )
            if scope == "同名动作" and shared:
                detail = f"（共同动作：{'、'.join(shared[:3])}{' 等' if len(shared) > 3 else ''}）"
            elif scope == "同部位":
                detail = (
                    f"（两次都练到的动作：{'、'.join(shared[:3])}{' 等' if len(shared) > 3 else ''}）"
                    if shared
                    else "（两次动作不同，但练的是同一部位）"
                )
            else:
                detail = ""
            bullets.append(
                f"- {scope_label}容量较上次（{prev_date}）{trend} "
                f"**{abs(java_pct):.1f}%**{detail}，{advice}"
            )

    # ② 全天总容量对比（需要能从上次记录算出总容量；与①口径明显不同才单独列）
    if prev_total and prev_total > 0:
        total_pct = (total_volume - prev_total) / prev_total * 100
        if java_pct is None or abs(total_pct - java_pct) >= 0.5:
            if abs(total_pct) < 0.05:
                bullets.append(
                    f"- 全天总容量与上次（{prev_date}）基本持平"
                    f"（{fmt_num(total_volume)}kg）"
                )
            else:
                trend = "提升" if total_pct >= 0 else "下降"
                bullets.append(
                    f"- 全天总容量较上次（{prev_date}）{trend} "
                    f"**{abs(total_pct):.1f}%**"
                    f"（{fmt_num(prev_total)}kg → {fmt_num(total_volume)}kg）"
                )

    prev_by_action: Dict[str, float] = prev.get("by_action") or {}
    if prev_by_action:
        for row in sorted(parsed, key=lambda r: -r["volume"])[:3]:
            old = prev_by_action.get(row["action"])
            if old and old > 0:
                delta = (row["volume"] - old) / old * 100
                if abs(delta) < 0.05:
                    bullets.append(
                        f"- {row['action']}：容量与上次持平（{fmt_num(row['volume'])}kg），"
                        "下次可尝试小幅加重或增加 1 组打破平台"
                    )
                else:
                    trend = "提升" if delta >= 0 else "下降"
                    bullets.append(
                        f"- {row['action']}：容量较上次{trend} **{abs(delta):.1f}%**"
                        f"（{fmt_num(old)}kg → {fmt_num(row['volume'])}kg）"
                    )
            else:
                bullets.append(
                    f"- {row['action']}：首次加入训练计划，动作模式需要 2-3 次训练适应，"
                    "先以动作质量为主、容量其次"
                )

    if not bullets:
        bullets.append(
            f"- 暂无上次同部位对比数据，本次作为基准记录（总容量 {fmt_num(total_volume)}kg），"
            "下次训练可据此评估进步幅度"
        )
        ranked = sorted(parsed, key=lambda r: -r["volume"])
        for row in ranked[:2]:
            share = (row["volume"] / total_volume * 100) if total_volume > 0 else 0.0
            if total_volume > 0:
                bullets.append(
                    f"- {row['action']}：贡献 {share:.1f}% 的今日总容量，"
                    f"RPE {row['rpe']}/10，{'可作为主项继续加重' if row is best else '建议作为辅助动作保持'}"
                )
    return bullets[:5]


def _suggestions(
    parsed: List[Dict[str, Any]],
    total_volume: float,
    best: Dict[str, Any],
    max_rpe: int,
    prev: Dict[str, Any],
) -> List[str]:
    """生成「💡 改进建议」（2-4 条，具体可执行）。"""
    tips: List[str] = []

    if best["weight"] > 0:
        target = round_to_plate(best["weight"] * 1.05)
        if target <= best["weight"]:
            target = best["weight"] + 2.5
        tips.append(
            f"下周可尝试 **{best['action']}** 递增至 {fmt_num(target)}kg"
            f"（{max(2, best['sets'] - 1)}组 × 8次），在动作质量不下降的前提下保持渐进超负荷"
        )
    else:
        tips.append(
            f"下周可为 **{best['action']}** 增加负重或使用更高难度的变式"
            "（自重动作同样需要持续增加刺激强度）"
        )

    cue = _technique_cue(best["action"])
    if cue:
        tips.append(cue)

    weakest = min(parsed, key=lambda r: r["volume"])
    if weakest["action"] != best["action"]:
        tips.append(
            f"**{weakest['action']}** 本次单动作容量最低（{fmt_num(weakest['volume'])}kg），"
            "建议增加 1 组或适当加重，补齐容量短板"
        )

    if max_rpe >= 9:
        tips.append(
            f"本次最高 RPE 达 {max_rpe}/10，已接近力竭：组间休息保持 2-3 分钟；"
            "若连续两次训练 RPE≥9，建议安排一个减载周"
        )
    elif max_rpe <= 6:
        tips.append(
            f"整体强度偏轻松（最高 RPE {max_rpe}/10），动作结束时仍有余力，"
            "下周可小幅提升重量或组数"
        )
    else:
        tips.append(
            "强度控制得当，继续保持「留 1-2 次余力」的训练习惯，避免每组都练到力竭"
        )

    pct = prev.get("pct")
    if pct is not None and pct < 0:
        tips.append("本次容量回落不必强行补量，优先保证睡眠与恢复，下周回到正常强度即可")

    return tips[:4]


def generate_summary(
    user_id: int,
    date_str: str,
    records: List[dict],
    comparison: Optional[dict] = None,
) -> SummaryResponse:
    """生成训练智能总结（规范 7.1，MVP 核心）。

    优先调用 **DeepSeek + 内置 Prompt 模板** 生成；未配置 Key 或调用失败时，
    回落到本地拼装实现（第 4 周已验证，数字全部由入参真实计算），
    保证「AI 教练暂时走神」时前端仍能拿到一份结构完整的总结，而不是白屏。

    :raises AgentInputError: records 为空或无法解析（HTTP 400）
    """
    if not records:
        raise AgentInputError("今日暂无训练记录，无法生成总结")
    parsed = _normalize_records(records)
    if not parsed:
        raise AgentInputError("训练记录格式不正确，无法解析出有效动作")

    try:
        return _generate_summary_with_llm(user_id, date_str, parsed, records, comparison)
    except Exception as exc:  # noqa: BLE001 - 降级是本函数的职责
        logger.warning(
            "大模型生成训练总结失败，回落到本地拼装: %s: %s", type(exc).__name__, exc
        )
    return _generate_summary_offline(user_id, date_str, parsed, comparison)


def _generate_summary_with_llm(
    user_id: int,
    date_str: str,
    parsed: List[Dict[str, Any]],
    raw_records: List[dict],
    comparison: Optional[dict],
) -> SummaryResponse:
    """真实 LLM 路径：SUMMARY_SYSTEM_PROMPT + SUMMARY_USER_PROMPT → DeepSeek。"""
    from .llm import get_llm

    llm = get_llm()
    if settings.mock_mode:
        # 用异常表达「此路不通」，由 generate_summary 统一降级，避免这里散落分支
        raise RuntimeError("MOCK_MODE=true，按配置跳过真实大模型调用")
    if not llm.configured:
        raise RuntimeError("未配置 DEEPSEEK_API_KEY")

    effective_date = (date_str or "").strip() or today_str()
    records_text = _format_records_for_prompt(parsed, effective_date)
    comparison_text = _format_comparison_for_prompt(comparison, parsed)

    logger.info(
        "调用大模型生成训练总结: userId=%s date=%s 动作数=%d 有对比数据=%s",
        user_id, effective_date, len(parsed), comparison is not None,
    )
    summary = llm.chat_with_system(
        SUMMARY_SYSTEM_PROMPT,
        SUMMARY_USER_PROMPT.format(
            records_text=records_text, comparison_text=comparison_text
        ),
        # 总结要基于给定数据、不要自由发挥，因此温度调低
        temperature=0.4,
        max_tokens=800,
    )
    logger.info(
        "训练总结生成完成(LLM): userId=%s 动作数=%d 字数=%d",
        user_id, len(parsed), len(summary),
    )
    return SummaryResponse(summary=summary, generated_at=now_local())


def _format_records_for_prompt(parsed: List[Dict[str, Any]], date_str: str) -> str:
    """把训练记录格式化成给大模型看的一段文本。

    刻意保留具体数字（组/次/重量/RPE），因为规范要求总结里要引用数据；
    同时附上服务端算好的单动作容量与总容量 —— 让大模型「只做归纳、不做算术」，
    避免它算错（大模型做乘法很容易出错，而这里的数据 Java 侧已经算准了）。
    """
    total_volume = sum(r["volume"] for r in parsed)
    lines = [f"日期：{date_str}"]
    for r in parsed:
        weight = f"{fmt_num(r['weight'])}kg" if r["weight"] > 0 else "自重"
        rpe = f"，RPE {r['rpe']}/10" if r["rpe"] > 0 else ""
        lines.append(
            f"- {r['action']}：{r['sets']}组 × {r['reps']}次 × {weight}"
            f"，单动作容量 {fmt_num(r['volume'])}kg{rpe}"
        )
    lines.append(f"- 合计：{len(parsed)} 个动作，总容量 {fmt_num(total_volume)}kg")
    return "\n".join(lines)


def _format_comparison_for_prompt(
    comparison: Optional[dict], parsed: List[Dict[str, Any]]
) -> str:
    """把 Java 传来的对比数据格式化成可读文本；没有则明确说明。

    Java 侧传的 comparison 结构（见 AiSummaryService.buildComparison）：
    ``{previousDate, scope, sharedMuscles, sharedActions, records, volumeChangePct}``

    其中 ``scope`` 标明对比口径，**必须原样告知大模型**：
    - ``scope=同部位``  → volumeChangePct 是「共同肌群」的容量变化
    - ``scope=同名动作`` → 它是「同名动作」的容量变化

    第 4 周踩过这个坑：当时只传了数字没传口径，大模型把「同名动作容量变化」
    写成了「总容量变化」，结论与数字对不上。所以这里把口径说死。
    """
    prev = _parse_comparison(comparison)
    if not comparison:
        return "无历史对比数据（这是用户的首次训练记录，或之前没有可对比的记录）"

    lines: List[str] = []
    previous_date = comparison.get("previousDate")
    if previous_date:
        lines.append(f"上次训练日期：{previous_date}")

    prev_records = comparison.get("records") or []
    if prev_records:
        lines.append("上次训练记录：")
        for item in prev_records[:10]:
            if not isinstance(item, dict):
                continue
            weight = item.get("weight")
            weight_text = f"{weight}kg" if weight else "自重"
            lines.append(
                f"- {item.get('action')}：{item.get('sets')}组 × "
                f"{item.get('reps')}次 × {weight_text}"
            )

    scope = comparison.get("scope")
    shared_muscles = comparison.get("sharedMuscles") or []
    shared_actions = comparison.get("sharedActions") or []

    if shared_muscles:
        lines.append(f"两次都练到的部位（肌群）：{'、'.join(str(m) for m in shared_muscles)}")
    if shared_actions:
        lines.append(f"两次都练到的动作：{'、'.join(str(a) for a in shared_actions)}")

    pct = comparison.get("volumeChangePct")
    if pct is not None:
        direction = "提升" if float(pct) >= 0 else "下降"
        if scope == "同部位":
            lines.append(
                f"共同肌群的训练容量较上次{direction} {abs(float(pct))}%"
                f"（口径：仅统计两次都练到的肌群，**不是全天总容量变化**，"
                f"请勿表述为总容量变化）"
            )
        else:
            lines.append(
                f"同名动作的训练容量较上次{direction} {abs(float(pct))}%"
                f"（口径：**仅同名动作**，不是全天总容量变化，请勿表述为总容量变化）"
            )

    if prev["total_volume"] is not None:
        lines.append(f"上次全天总容量：{fmt_num(prev['total_volume'])}kg")

    return "\n".join(lines)


def _generate_summary_offline(
    user_id: int,
    date_str: str,
    parsed: List[Dict[str, Any]],
    comparison: Optional[dict],
) -> SummaryResponse:
    """离线兜底：完全本地拼装（不调用大模型），所有数字由入参真实计算。"""
    date_str = (date_str or "").strip() or today_str()
    total_volume = sum(r["volume"] for r in parsed)
    total_sets = sum(r["sets"] for r in parsed)
    total_reps = sum(r["sets"] * r["reps"] for r in parsed)
    total_duration = sum(r["duration"] for r in parsed)
    rpe_values = [r["rpe"] for r in parsed if r["rpe"] > 0]
    max_rpe = max(rpe_values) if rpe_values else 0
    best = max(parsed, key=lambda r: r["volume"])
    prev = _parse_comparison(comparison)

    lines: List[str] = ["## 🏋️ 今日训练总结", "", "### 📊 训练概览"]

    muscles = _infer_muscles(parsed)
    muscle_text = f"（{'、'.join(muscles)}）" if muscles else ""
    overview = (
        f"{date_str} 共完成 **{len(parsed)}** 个训练动作{muscle_text}，"
        f"累计 **{total_sets}** 组 / **{total_reps}** 次"
    )
    if total_volume > 0:
        overview += f"，总容量 **{fmt_num(total_volume)}kg**"
    else:
        overview += "（本次以自重或轻负荷为主，未产生有效容量）"
    if total_duration > 0:
        overview += f"，训练时长约 **{total_duration}** 分钟"
    overview += f"。{_intensity_phrase(max_rpe)}。"
    lines.append(overview)

    lines += ["", "### ⭐ 最佳表现"]
    weight_text = f" × {fmt_num(best['weight'])}kg" if best["weight"] > 0 else "（自重）"
    best_line = f"**{best['action']}**：{best['sets']}组 × {best['reps']}次{weight_text}"
    if total_volume > 0:
        share = best["volume"] / total_volume * 100
        best_line += (
            f"，单动作容量 **{fmt_num(best['volume'])}kg**（占今日总容量 {share:.1f}%）"
        )
    best_line += f"，RPE {best['rpe']}/10，{_praise(best['rpe'])}"
    lines.append(best_line)

    lines += ["", "### 📈 对比分析"]
    lines.extend(_comparison_bullets(parsed, total_volume, prev, best))

    lines += ["", "### 💡 改进建议"]
    for index, tip in enumerate(
        _suggestions(parsed, total_volume, best, max_rpe, prev), start=1
    ):
        lines.append(f"{index}. {tip}")

    summary = "\n".join(lines)
    logger.info(
        "训练总结生成完成(本地拼装): userId=%s 动作数=%d 总容量=%s 最佳动作=%s 对比数据=%s",
        user_id,
        len(parsed),
        fmt_num(total_volume),
        best["action"],
        "有" if (prev["pct"] is not None or prev["total_volume"] is not None) else "无",
    )
    return SummaryResponse(summary=summary, generated_at=now_local())


# ============================================================
# 二、动作推荐
# ============================================================

#: 无需器械（自重/垫上）的动作条件，任何环境下都视为「可用」
NO_EQUIPMENT = {"自重", "垫子", "瑜伽垫", "无", "徒手"}

#: 各肌群真实存在的训练动作库（复合动作在前，孤立动作在后 —— 对应规范的推荐原则）
ACTION_LIBRARY: Dict[str, List[Dict[str, Any]]] = {
    "胸": [
        {"action_name": "平板杠铃卧推", "focus_area": "胸大肌整体", "recommended_sets": "4组", "recommended_reps": "6-10次", "difficulty": "进阶", "notes": "肩胛骨全程收紧下沉，杠铃下放至胸骨中下部，推起时不要锁死肘关节", "equipment": ["杠铃", "卧推凳"]},
        {"action_name": "上斜哑铃卧推", "focus_area": "上胸", "recommended_sets": "3-4组", "recommended_reps": "8-12次", "difficulty": "进阶", "notes": "凳角调至30-45度，哑铃下放至胸两侧，推起时勿锁死肘关节", "equipment": ["哑铃", "可调节凳"]},
        {"action_name": "俯卧撑", "focus_area": "胸大肌整体", "recommended_sets": "3-4组", "recommended_reps": "15-20次", "difficulty": "新手", "notes": "身体保持一条直线，下落时肘部与躯干约成45度，胸口贴近地面", "equipment": ["自重"]},
        {"action_name": "哑铃飞鸟", "focus_area": "胸大肌外沿（拉伸位）", "recommended_sets": "3组", "recommended_reps": "10-15次", "difficulty": "新手", "notes": "手肘微屈固定角度，用「抱大树」的轨迹画弧，最低点感受胸肌拉伸即可", "equipment": ["哑铃", "平凳"]},
        {"action_name": "绳索夹胸", "focus_area": "胸大肌中缝", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "进阶", "notes": "身体略前倾，双手在胸前沿弧线交叉挤压，顶峰停顿 1 秒", "equipment": ["龙门架", "绳索"]},
        {"action_name": "器械推胸", "focus_area": "胸大肌整体", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "座椅高度让把手与胸部中段齐平，离心阶段控制 2 秒", "equipment": ["推胸机"]},
        {"action_name": "下斜哑铃卧推", "focus_area": "下胸", "recommended_sets": "3组", "recommended_reps": "8-12次", "difficulty": "进阶", "notes": "下斜角度 15-30 度，注意固定腿部避免滑动，重量不宜过大", "equipment": ["哑铃", "可调节凳"]},
        {"action_name": "蝴蝶机夹胸", "focus_area": "胸大肌中缝", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "肩胛骨贴紧靠背，用胸肌发力夹合，避免用手臂甩动", "equipment": ["蝴蝶机"]},
        {"action_name": "上斜杠铃卧推", "focus_area": "上胸", "recommended_sets": "4组", "recommended_reps": "6-8次", "difficulty": "进阶", "notes": "杠铃落点在锁骨下方一拳处，全程保持肩胛稳定", "equipment": ["杠铃", "可调节凳"]},
        {"action_name": "双杠臂屈伸（前倾）", "focus_area": "下胸", "recommended_sets": "3组", "recommended_reps": "8-12次", "difficulty": "高级", "notes": "躯干前倾、双腿后摆，下落至肩略低于肘，肩部不适立即停止", "equipment": ["双杠架", "自重"]},
    ],
    "背": [
        {"action_name": "引体向上", "focus_area": "背阔肌宽度", "recommended_sets": "4组", "recommended_reps": "6-10次", "difficulty": "高级", "notes": "先沉肩再拉，胸口向单杠靠近，避免耸肩和摆腿借力", "equipment": ["单杠", "自重"]},
        {"action_name": "高位下拉", "focus_area": "背阔肌宽度", "recommended_sets": "4组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "握距略宽于肩，肩胛先下沉，把横杆拉到锁骨位置", "equipment": ["高位下拉机"]},
        {"action_name": "杠铃划船", "focus_area": "背部厚度（中背）", "recommended_sets": "4组", "recommended_reps": "8-10次", "difficulty": "进阶", "notes": "躯干前倾约45度并锁住腰背，拉向下腹部，避免用腰部起伏借力", "equipment": ["杠铃"]},
        {"action_name": "单臂哑铃划船", "focus_area": "背阔肌单侧", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "一手一膝支撑在平凳上，肘部贴近身体向后上方拉，避免躯干旋转", "equipment": ["哑铃", "平凳"]},
        {"action_name": "坐姿绳索划船", "focus_area": "中背/菱形肌", "recommended_sets": "3-4组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "挺胸收腹，肩胛主动后缩，回放时不要含胸驼背", "equipment": ["划船机", "绳索"]},
        {"action_name": "硬拉", "focus_area": "竖脊肌/后链整体", "recommended_sets": "4组", "recommended_reps": "5-8次", "difficulty": "高级", "notes": "杠铃贴腿垂直起落，先锁背再发力，全程保持脊柱中立", "equipment": ["杠铃"]},
        {"action_name": "直臂下压", "focus_area": "背阔肌下沿", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "进阶", "notes": "手臂近似伸直，用背阔肌把绳索压向大腿，避免变成三头下压", "equipment": ["龙门架", "绳索"]},
        {"action_name": "T杠划船", "focus_area": "背部厚度", "recommended_sets": "3组", "recommended_reps": "8-12次", "difficulty": "进阶", "notes": "核心收紧、腰背挺直，拉至胸口下沿并挤压肩胛", "equipment": ["T杠", "杠铃片"]},
        {"action_name": "反向飞鸟", "focus_area": "后束/上背", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "小重量即可，肘部微屈向两侧打开，感受上背收缩", "equipment": ["哑铃"]},
        {"action_name": "面拉", "focus_area": "上背/后束", "recommended_sets": "3组", "recommended_reps": "15-20次", "difficulty": "新手", "notes": "绳索拉向面部，肘部高于手腕，末端外旋挤压上背，改善圆肩", "equipment": ["龙门架", "绳索"]},
    ],
    "腿": [
        {"action_name": "杠铃深蹲", "focus_area": "股四头肌/臀大肌", "recommended_sets": "4组", "recommended_reps": "6-10次", "difficulty": "进阶", "notes": "核心收紧、膝盖与脚尖同向，下蹲至大腿与地面平行，重心在脚掌中部", "equipment": ["杠铃", "深蹲架"]},
        {"action_name": "腿举", "focus_area": "股四头肌", "recommended_sets": "4组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "脚踩踏板中部，下放至膝角约90度，不要锁死膝关节", "equipment": ["腿举机"]},
        {"action_name": "罗马尼亚硬拉", "focus_area": "腘绳肌/臀大肌", "recommended_sets": "4组", "recommended_reps": "8-10次", "difficulty": "进阶", "notes": "膝盖微屈固定，以髋关节铰链下放，感受大腿后侧拉伸再顶髋站起", "equipment": ["杠铃"]},
        {"action_name": "保加利亚分腿蹲", "focus_area": "臀/股四头肌（单侧）", "recommended_sets": "3组", "recommended_reps": "8-12次", "difficulty": "进阶", "notes": "后脚搭凳高度以膝盖不碰地为准，前脚跟发力、躯干保持直立", "equipment": ["哑铃", "平凳"]},
        {"action_name": "腿屈伸", "focus_area": "股四头肌（孤立）", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "顶峰位置停顿 1 秒，离心阶段慢慢下放，膝关节不适时减重", "equipment": ["腿屈伸机"]},
        {"action_name": "腿弯举", "focus_area": "腘绳肌（孤立）", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "髋部贴紧座椅不要抬起，用大腿后侧发力卷起", "equipment": ["腿弯举机"]},
        {"action_name": "臀桥", "focus_area": "臀大肌", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "顶髋至躯干与大腿成一条直线，顶峰夹紧臀部 1 秒，避免用腰发力", "equipment": ["杠铃", "垫子"]},
        {"action_name": "哑铃箭步蹲", "focus_area": "臀/股四头肌", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "前膝不超过脚尖太多，后膝轻触地面，保持躯干直立", "equipment": ["哑铃"]},
        {"action_name": "杠铃颈前深蹲", "focus_area": "股四头肌", "recommended_sets": "4组", "recommended_reps": "6-8次", "difficulty": "高级", "notes": "杠铃置于三角肌前束，肘部始终抬高，躯干尽量直立", "equipment": ["杠铃", "深蹲架"]},
        {"action_name": "坐姿提踵", "focus_area": "小腿三头肌", "recommended_sets": "4组", "recommended_reps": "15-20次", "difficulty": "新手", "notes": "全程幅度拉满，最高点停顿 1 秒，离心阶段 2-3 秒", "equipment": ["提踵机"]},
    ],
    "肩": [
        {"action_name": "坐姿哑铃推举", "focus_area": "三角肌前束/中束", "recommended_sets": "4组", "recommended_reps": "8-12次", "difficulty": "进阶", "notes": "靠背支撑保持腰椎中立，哑铃推到头顶上方稍靠前的位置", "equipment": ["哑铃", "靠背凳"]},
        {"action_name": "杠铃站姿推举", "focus_area": "三角肌/整体力量", "recommended_sets": "4组", "recommended_reps": "6-8次", "difficulty": "高级", "notes": "臀腿收紧避免腰椎超伸，杠铃走贴近面部的垂直轨迹", "equipment": ["杠铃"]},
        {"action_name": "哑铃侧平举", "focus_area": "三角肌中束", "recommended_sets": "3-4组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "肘部微屈、小指略高于拇指，抬至与肩同高即可，避免耸肩甩动", "equipment": ["哑铃"]},
        {"action_name": "绳索侧平举", "focus_area": "三角肌中束", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "进阶", "notes": "绳索放在身体后方提供持续张力，全程控制不要借力", "equipment": ["龙门架", "绳索"]},
        {"action_name": "哑铃前平举", "focus_area": "三角肌前束", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "手臂微屈抬至肩高，避免躯干后仰借力", "equipment": ["哑铃"]},
        {"action_name": "反向飞鸟", "focus_area": "三角肌后束", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "俯身或俯卧凳上，用后束发力向两侧打开，小重量高次数更有效", "equipment": ["哑铃"]},
        {"action_name": "面拉", "focus_area": "后束/上背", "recommended_sets": "3组", "recommended_reps": "15-20次", "difficulty": "新手", "notes": "拉向面部并外旋，强化后束与肩袖，久坐人群强烈推荐", "equipment": ["龙门架", "绳索"]},
        {"action_name": "阿诺德推举", "focus_area": "三角肌前束/中束", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "进阶", "notes": "下放时掌心相对并内旋，推起过程中旋转至掌心向前，节奏放慢", "equipment": ["哑铃", "靠背凳"]},
        {"action_name": "器械推肩", "focus_area": "三角肌整体", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "把手与肩同高，轨迹固定时更专注离心控制", "equipment": ["推肩机"]},
        {"action_name": "直立划船", "focus_area": "三角肌中束/斜方肌", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "进阶", "notes": "拉到胸口高度即可，肘部带动向上；肩关节有弹响或不适请换成侧平举", "equipment": ["杠铃", "曲杆"]},
    ],
    "手臂": [
        {"action_name": "杠铃弯举", "focus_area": "肱二头肌", "recommended_sets": "3-4组", "recommended_reps": "8-12次", "difficulty": "新手", "notes": "大臂固定夹紧躯干，离心阶段放慢到 2-3 秒", "equipment": ["杠铃", "曲杆"]},
        {"action_name": "哑铃交替弯举", "focus_area": "肱二头肌", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "顶点可稍微外旋手腕增强收缩，避免身体摆动借力", "equipment": ["哑铃"]},
        {"action_name": "锤式弯举", "focus_area": "肱肌/肱桡肌", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "掌心相对握法，增加手臂外侧厚度，肘部贴紧身体", "equipment": ["哑铃"]},
        {"action_name": "牧师凳弯举", "focus_area": "肱二头肌（孤立）", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "进阶", "notes": "腋下贴紧斜板，下放到底时保持张力不要完全放松", "equipment": ["牧师凳", "曲杆"]},
        {"action_name": "绳索下压", "focus_area": "肱三头肌", "recommended_sets": "3-4组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "肘部固定在体侧，只让前臂运动，末端完全伸直并挤压", "equipment": ["龙门架", "绳索"]},
        {"action_name": "窄距卧推", "focus_area": "肱三头肌/胸", "recommended_sets": "4组", "recommended_reps": "6-10次", "difficulty": "进阶", "notes": "握距约与肩同宽，肘部贴近身体，杠铃落点偏下胸", "equipment": ["杠铃", "卧推凳"]},
        {"action_name": "仰卧臂屈伸", "focus_area": "肱三头肌长头", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "进阶", "notes": "曲杆下放至额头上方，肘部指向天花板并保持不动", "equipment": ["曲杆", "平凳"]},
        {"action_name": "双杠臂屈伸（直立）", "focus_area": "肱三头肌", "recommended_sets": "3组", "recommended_reps": "8-12次", "difficulty": "高级", "notes": "躯干保持直立、肘部向后，下落至肘角90度再推起", "equipment": ["双杠架", "自重"]},
        {"action_name": "绳索过顶臂屈伸", "focus_area": "肱三头肌长头", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "背对龙门架、身体前倾，大臂固定在耳侧，充分拉伸长头", "equipment": ["龙门架", "绳索"]},
        {"action_name": "反握弯举", "focus_area": "肱肌/前臂", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "正握（掌心向下）小重量完成，可改善前臂与握力", "equipment": ["曲杆"]},
    ],
    "核心": [
        {"action_name": "平板支撑", "focus_area": "腹横肌/核心稳定", "recommended_sets": "3组", "recommended_reps": "30-60秒", "difficulty": "新手", "notes": "肘在肩正下方，臀部不要塌陷或过高，全程正常呼吸", "equipment": ["垫子", "自重"]},
        {"action_name": "卷腹", "focus_area": "上腹", "recommended_sets": "3组", "recommended_reps": "15-20次", "difficulty": "新手", "notes": "下巴微收，用腹部把肩胛卷离地面，避免用手拽脖子", "equipment": ["垫子"]},
        {"action_name": "悬垂举腿", "focus_area": "下腹", "recommended_sets": "3组", "recommended_reps": "10-15次", "difficulty": "高级", "notes": "先稳定肩胛再抬腿，避免前后摆动借力，可先做屈膝版本", "equipment": ["单杠", "自重"]},
        {"action_name": "俄罗斯转体", "focus_area": "腹斜肌", "recommended_sets": "3组", "recommended_reps": "15-20次", "difficulty": "新手", "notes": "转动来自胸椎而非手臂，保持腰背挺直、动作有控制", "equipment": ["药球", "垫子"]},
        {"action_name": "死虫式", "focus_area": "腹横肌/核心稳定", "recommended_sets": "3组", "recommended_reps": "10-12次", "difficulty": "新手", "notes": "下背始终贴地，对侧手脚缓慢伸展，呼气时收紧腹部", "equipment": ["垫子"]},
        {"action_name": "绳索卷腹", "focus_area": "上腹", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "进阶", "notes": "跪姿固定髋部，用腹部把躯干向下卷，不要用手臂拉绳", "equipment": ["龙门架", "绳索"]},
        {"action_name": "健腹轮", "focus_area": "核心整体（抗伸展）", "recommended_sets": "3组", "recommended_reps": "8-12次", "difficulty": "高级", "notes": "骨盆后倾、臀部夹紧，前推距离循序渐进，腰部塌陷立即停止", "equipment": ["健腹轮"]},
        {"action_name": "侧平板支撑", "focus_area": "腹斜肌", "recommended_sets": "3组", "recommended_reps": "30-45秒", "difficulty": "新手", "notes": "身体保持一条直线，髋部主动向上顶，两侧时间均等", "equipment": ["垫子"]},
        {"action_name": "反向卷腹", "focus_area": "下腹", "recommended_sets": "3组", "recommended_reps": "12-15次", "difficulty": "新手", "notes": "用下腹把骨盆卷向胸口，避免用腿部惯性甩动", "equipment": ["垫子"]},
        {"action_name": "农夫行走", "focus_area": "核心/握力", "recommended_sets": "3组", "recommended_reps": "30-40米", "difficulty": "进阶", "notes": "挺胸沉肩、步幅稳定，重量以能保持躯干直立为准", "equipment": ["哑铃", "壶铃"]},
    ],
}

#: 全身/综合：直接复用复合动作（第 4 周的兜底肌群，避免 Java 传「全身」时无数据）
ACTION_LIBRARY["全身"] = [
    ACTION_LIBRARY["腿"][0],   # 杠铃深蹲
    ACTION_LIBRARY["胸"][0],   # 平板杠铃卧推
    ACTION_LIBRARY["背"][5],   # 硬拉
    ACTION_LIBRARY["背"][0],   # 引体向上
    ACTION_LIBRARY["肩"][1],   # 杠铃站姿推举
    ACTION_LIBRARY["背"][2],   # 杠铃划船
    ACTION_LIBRARY["核心"][9],  # 农夫行走
    ACTION_LIBRARY["腿"][3],   # 保加利亚分腿蹲
]

#: 肌群别名（含中文俗称与英文），把 Java/前端的各种写法归一到标准键
MUSCLE_ALIASES: Dict[str, str] = {
    "胸": "胸", "胸部": "胸", "胸肌": "胸", "胸大肌": "胸", "上胸": "胸", "chest": "胸",
    "背": "背", "背部": "背", "后背": "背", "背阔肌": "背", "上背": "背", "back": "背",
    "腿": "腿", "腿部": "腿", "下肢": "腿", "大腿": "腿", "腿臀": "腿", "leg": "腿", "legs": "腿",
    "肩": "肩", "肩部": "肩", "三角肌": "肩", "肩臂": "肩", "shoulder": "肩",
    "手臂": "手臂", "胳膊": "手臂", "二头": "手臂", "三头": "手臂", "臂": "手臂", "arm": "手臂", "arms": "手臂",
    "核心": "核心", "腹部": "核心", "腹肌": "核心", "腰腹": "核心", "core": "核心",
    "全身": "全身", "全身性": "全身", "综合": "全身", "full body": "全身", "fullbody": "全身",
}


def _normalize_muscle(target_muscle: str) -> Optional[str]:
    """把目标肌群归一化；无法识别返回 None。"""
    text = str(target_muscle or "").strip().lower()
    if not text:
        return None
    if text in MUSCLE_ALIASES:
        return MUSCLE_ALIASES[text]
    for key in ACTION_LIBRARY:
        if key in text:
            return key
    for alias, standard in MUSCLE_ALIASES.items():
        if alias in text:
            return standard
    return None


def _norm_equipment(name: Any) -> str:
    """器械名归一化（去空格、去常见后缀）。"""
    text = str(name or "").strip().lower().replace(" ", "")
    for suffix in ("器材", "设备"):
        if text.endswith(suffix):
            text = text[: -len(suffix)]
    return text


def _equipment_rank(required: List[str], available: set) -> Tuple[int, float]:
    """动作的可用性层级与器械匹配度，返回 ``(tier, score)``。

    - ``tier = 0``：命中用户器械（按「命中数 / 所需数」算 ``score``），优先推荐；
    - ``tier = 1``：自重/垫上动作（不需要器械，永远可做），但排在命中器械的动作之后，
      只有用户没有对应器械时才作为补充出现；
    - ``tier = -1``：器械不足，做不了，直接排除。

    匹配采用宽松子串比较，兼容「可调凳」/「可调节凳」、「龙门架」/「龙门架带绳索」等写法。
    """
    required_norm = [_norm_equipment(item) for item in (required or [])]
    if not required_norm or all(item in NO_EQUIPMENT for item in required_norm):
        return 1, 1.0
    hits = 0
    for need in required_norm:
        if need in NO_EQUIPMENT:
            continue
        for have in available:
            if need == have or need in have or have in need:
                hits += 1
                break
    if not hits:
        return -1, 0.0
    return 0, hits / len(required_norm)


def _ensure_difficulty_coverage(
    picked: List[Dict[str, Any]], remaining: List[Dict[str, Any]]
) -> List[Dict[str, Any]]:
    """推荐原则：难度要覆盖新手与进阶。

    若挑出的动作清一色进阶/高级，则用剩下的新手动作替换最后一条。
    """
    if len(picked) < 2 or any(item["difficulty"] == "新手" for item in picked):
        return picked
    beginner = next(
        (item for item in remaining if item["difficulty"] == "新手"), None
    )
    if beginner is None:
        return picked
    return picked[:-1] + [beginner]


def recommend_actions(
    target_muscle: str, equipment: List[str], count: int = 5
) -> RecommendResponse:
    """按目标肌群 + 可用器械推荐动作（第 6 周：LLM 生成 + 规则引擎兜底）。

    **为什么不是「让 LLM 自由发挥」**：前端要用动作名去查训练记录、
    匹配动作库与姿态评估的素材，所以动作名必须**可枚举**。
    因此流程是：
    1. 先用规则引擎算出「用户器械做得了」的候选动作集合；
    2. 把候选集作为上下文交给 LLM，要求它**只能从候选集里挑**并排序、写要点；
    3. 对返回的 ``actionName`` 做白名单校验，越界的条目直接丢弃；
    4. LLM 不可用 / 返回非法 JSON / 全部条目越界 → 回退到规则引擎的完整结果。

    这样最坏情况与第 4 周的行为完全一致，接口永远不会因为「模型抽风」而失败。

    :raises AgentInputError: 肌群无法识别或器械列表为空（HTTP 400）
    """
    logger.info(
        "开始动作推荐: targetMuscle=%s equipment=%s count=%d",
        target_muscle,
        equipment,
        count,
    )

    muscle = _normalize_muscle(target_muscle)
    if muscle is None:
        raise AgentInputError(
            f"不支持的目标肌群：{target_muscle}；可选值：胸/背/腿/肩/手臂/核心/全身"
        )

    available = {_norm_equipment(item) for item in (equipment or []) if str(item).strip()}
    if not available:
        raise AgentInputError("可用器械列表不能为空")

    # MOCK_MODE=true：不调用任何大模型，直接走规则引擎（离线 / 无 Key 演示路径）
    if settings.mock_mode:
        logger.warning("MOCK_MODE=true：动作推荐走内置动作库规则引擎")
        return _recommend_actions_local(muscle, available, count)

    try:
        return _recommend_actions_llm(muscle, available, count)
    except Exception as exc:  # noqa: BLE001 - LLM 只是增强项，失败必须回退而不是让接口失败
        logger.warning("LLM 动作推荐失败，回退规则引擎: %s: %s", type(exc).__name__, exc)
        return _recommend_actions_local(muscle, available, count)


def _recommend_actions_local(
    muscle: str, available: Set[str], count: int
) -> RecommendResponse:
    """规则引擎实现：从内置动作库按「器械匹配度 → 难度覆盖」筛选。

    第 4 周交付的原始实现，现在作为 LLM 不可用时的兜底路径
    （也是 MOCK_MODE=true 时的唯一路径）。
    """
    pool = ACTION_LIBRARY[muscle]
    scored: List[Tuple[int, float, int, Dict[str, Any]]] = []
    for index, item in enumerate(pool):
        tier, match = _equipment_rank(item["equipment"], available)
        scored.append((tier, match, index, item))

    # 只保留「用户做得了」的动作，再排序：
    # 命中器械(tier 0)优先 → 匹配度高的优先 → 自重动作兜底 → 同分保持动作库原始顺序（复合动作在前）
    feasible = [row for row in scored if row[0] >= 0]
    if not any(row[0] == 0 for row in feasible):
        logger.warning(
            "器械 %s 与肌群 %s 的动作库无交集，降级返回自重/垫上动作", available, muscle
        )
    ordered = sorted(feasible, key=lambda row: (row[0], -row[1], row[2]))

    picked = [row[3] for row in ordered[:count]]
    picked = _ensure_difficulty_coverage(picked, [row[3] for row in ordered[count:]])
    if len(picked) < count:
        # 器械受限导致候选不足：如实少返回，并留日志便于排查（不推荐用户做不了的动作）
        logger.warning(
            "肌群 %s 在器械 %s 下仅有 %d 个可用动作（请求 %d 个）",
            muscle,
            available,
            len(ordered),
            count,
        )

    recommendations = [
        ActionRecommendation(
            action_name=item["action_name"],
            target_muscle=muscle,
            focus_area=item["focus_area"],
            recommended_sets=item["recommended_sets"],
            recommended_reps=item["recommended_reps"],
            difficulty=item["difficulty"],
            notes=item["notes"],
            equipment=list(item["equipment"]),
        )
        for item in picked
    ]
    logger.info(
        "动作推荐完成(规则引擎): 肌群=%s 可用动作=%d 返回=%d 条 (%s)",
        muscle,
        len(ordered),
        len(recommendations),
        "、".join(item.action_name for item in recommendations),
    )
    return RecommendResponse(recommendations=recommendations, generated_at=now_local())


def _recommend_actions_llm(
    muscle: str, available: Set[str], count: int
) -> RecommendResponse:
    """LLM 生成推荐 + 动作名白名单校验（第 6 周）。

    流程：
    1. 先用规则引擎的筛选规则算出「该肌群 + 该器械下用户做得了」的候选动作；
    2. 把候选集塞进 Prompt，要求模型**只能从候选集里挑**；
    3. 解析 JSON，逐条做白名单校验 —— 越界的动作名直接丢弃（避免出现训练记录里
       查不到、动作库也没有的"野生动作名"）；
    4. 数值/文案字段做健壮性兜底：模型没给或给了非法值，就用动作库里的策划值。

    :raises LLMError: 未配置 Key、调用失败、返回非法 JSON、所有条目都越界
    """
    from .llm import LLMError, get_llm

    llm = get_llm()
    if not llm.configured:
        raise LLMError("未配置 DEEPSEEK_API_KEY，动作推荐无法调用大模型")

    # --- 1. 候选集（与规则引擎同一套「可行性」判定，保证推荐的动作用户真的能做）---
    pool = ACTION_LIBRARY[muscle]
    by_name = {item["action_name"]: item for item in pool}
    feasible = [
        item for item in pool
        if _equipment_rank(item["equipment"], available)[0] >= 0
    ]
    if not feasible:
        # 该肌群没有任何用户做得了的动作：交给规则引擎去走"降级返回自重动作"的分支
        raise LLMError(f"肌群 {muscle} 在器械 {sorted(available)} 下没有候选动作")

    candidates = "、".join(
        f"{item['action_name']}（{item['difficulty']}，器械：{'/'.join(item['equipment'])}）"
        for item in feasible
    )
    # 白名单 = 候选集本身，而不是「整个动作库」：
    # 动作库里有些动作用户当前器械做不了（如只有杠铃时不能推哑铃），
    # 若用整个库当白名单，模型一旦推荐了这些动作就会照样返回，
    # 前端就会给用户推一个他做不了的动作。
    white_list = {item["action_name"] for item in feasible}

    # --- 2. 调用 LLM ---
    text = llm.chat_with_system(
        RECOMMEND_SYSTEM_PROMPT.format(),
        RECOMMEND_USER_PROMPT.format(
            muscle=muscle,
            equipment="、".join(sorted(available)),
            candidates=candidates,
            count=count,
        ),
        temperature=0.4,
        max_tokens=1600,
    )

    payload = extract_json_object(text)
    if not payload or not isinstance(payload.get("recommendations"), list):
        raise LLMError(f"动作推荐返回的不是预期 JSON: {truncate(text, 200)}")

    # --- 3. 白名单校验 + 字段兜底 ---
    recommendations: List[ActionRecommendation] = []
    seen: Set[str] = set()
    rejected: List[str] = []
    for row in payload["recommendations"]:
        if not isinstance(row, dict):
            continue
        name = str(row.get("actionName") or row.get("action_name") or "").strip()
        if name not in white_list:
            rejected.append(name or "(空动作名)")
            continue
        if name in seen:
            continue
        seen.add(name)

        planned = by_name[name]     # 动作库里的策划值：字段兜底的来源
        recommendations.append(ActionRecommendation(
            action_name=name,
            target_muscle=muscle,
            focus_area=_clean_text(row.get("focusArea"), planned["focus_area"], 100),
            recommended_sets=_clean_text(
                row.get("recommendedSets"), planned["recommended_sets"], 20),
            recommended_reps=_clean_text(
                row.get("recommendedReps"), planned["recommended_reps"], 20),
            difficulty=(str(row.get("difficulty")).strip()
                        if str(row.get("difficulty") or "").strip() in ("新手", "进阶", "高级")
                        else planned["difficulty"]),
            notes=_clean_text(row.get("notes"), planned["notes"], 200),
            equipment=_clean_equipment(row.get("equipment"), planned["equipment"]),
        ))
        if len(recommendations) >= count:
            break

    if rejected:
        # 越界动作名要留痕：如果频繁出现，说明 Prompt 的约束不够强
        logger.warning("LLM 推荐中出现 %d 个不在动作库的动作名，已丢弃: %s",
                       len(rejected), "、".join(rejected[:5]))
    if not recommendations:
        raise LLMError("LLM 推荐的动作名全部不在动作库白名单内")

    logger.info(
        "动作推荐完成(LLM %s): 肌群=%s 候选=%d 返回=%d 条 (%s)",
        llm.model,
        muscle,
        len(feasible),
        len(recommendations),
        "、".join(item.action_name for item in recommendations),
    )
    return RecommendResponse(recommendations=recommendations, generated_at=now_local())


def _clean_text(value: Any, fallback: str, max_length: int) -> str:
    """取模型给的文本；空值/非法类型/超长时回退到动作库里的策划值。"""
    text = str(value).strip() if value is not None else ""
    if not text:
        return fallback
    return text if len(text) <= max_length else text[:max_length]


def _clean_equipment(value: Any, fallback: List[str]) -> List[str]:
    """规范化器械字段：只接受非空字符串列表，否则用动作库的值。"""
    if isinstance(value, list):
        items = [str(v).strip() for v in value if str(v or "").strip()]
        if items:
            return items[:5]
    return list(fallback)


# ============================================================
# 三、姿态评估
# ============================================================

#: 各动作的评估素材库（问题 / 纠正建议 / 优点），分数高低决定取前几条
POSE_EVALUATION_LIBRARY: Dict[str, Dict[str, List[str]]] = {
    "深蹲": {
        "issues": [
            "膝盖轻微内扣（内旋约10度）",
            "下背稍有弯曲，核心未充分收紧",
            "下蹲深度不足，大腿未达到与地面平行",
            "重心偏前，脚跟有轻微离地趋势",
        ],
        "suggestions": [
            "下蹲时有意识地向外打开膝盖，保持与脚尖方向一致",
            "收紧腹部，想象肚脐向脊柱靠拢，保持脊柱中立位",
            "降低重量，先练到髋关节低于膝盖的完整幅度再逐步加量",
            "把重心压在脚掌中部，必要时先做踝关节活动度训练",
        ],
        "good_points": [
            "髋关节活动度良好，下蹲深度达标",
            "杠铃轨迹基本垂直",
            "起立阶段发力顺序正确，髋膝同步伸展",
        ],
    },
    "卧推": {
        "issues": [
            "肩胛骨未全程收紧，肩部有代偿前移",
            "杠铃落点偏高（接近锁骨），前束参与过多",
            "肘部外展角度过大（接近90度），肩关节压力偏高",
            "推起时臀部离开凳面，桥式代偿明显",
        ],
        "suggestions": [
            "卧推前先沉肩夹背，把肩胛骨锁在凳面上再起杠",
            "杠铃落点控制在胸骨中下部（乳头连线上方），保持前臂垂直",
            "肘部与躯干保持约45-60度夹角，减少肩关节剪切力",
            "双脚踩实地面，臀部全程贴凳，用腿部驱动稳定躯干",
        ],
        "good_points": [
            "握距合理，前臂在最低点基本垂直地面",
            "上推阶段轨迹稳定，没有明显左右偏移",
            "触胸停顿控制良好，未出现弹胸借力",
        ],
    },
    "硬拉": {
        "issues": [
            "起始位下背轻微弯曲，腰部先于腿部发力",
            "杠铃离小腿过远，重心前移导致腰部负担增加",
            "锁定阶段过度后仰，腰椎超伸",
            "髋部起得比肩部快，动作变成「先直腿再拉背」",
        ],
        "suggestions": [
            "起拉前先「锁背」：挺胸、收紧背阔肌，再蹬地发力",
            "让杠铃贴着小腿垂直上下，肩关节始终位于杠铃正上方",
            "锁定只需站直并夹紧臀部，不需要向后仰",
            "降低重量，用「腿先动、髋跟上」的节奏重建动作模式",
        ],
        "good_points": [
            "握距与站距匹配良好，手臂全程垂直",
            "锁定阶段臀部收缩充分",
        ],
    },
    "推举": {
        "issues": [
            "推起时腰椎超伸，躯干后仰幅度偏大",
            "杠铃/哑铃轨迹偏前，肩关节压力增加",
            "核心与臀部未收紧，力量从下肢泄漏",
            "下放位置过低，肩关节处于高风险角度",
        ],
        "suggestions": [
            "推举前收紧臀部与腹部，把肋骨「拉下来」保持躯干直立",
            "让重量走贴近面部的垂直轨迹，头顶正上方为最高点",
            "下放至下巴或耳侧高度即可，不必追求过深",
            "先降低重量，用坐姿靠背版本练习轨迹稳定性",
        ],
        "good_points": [
            "双侧发力对称，没有明显代偿偏移",
            "离心阶段控制稳定，节奏均匀",
        ],
    },
    "划船": {
        "issues": [
            "耸肩明显，斜方肌上束代偿",
            "躯干起伏借力，腰部参与过多",
            "肘部外展过大，背阔肌发力感不足",
            "回放阶段含胸，肩胛未充分前伸",
        ],
        "suggestions": [
            "先沉肩再启动，用背阔肌带动肘部向后下方拉",
            "躯干角度固定，核心收紧，避免用腰部起伏借力",
            "肘部贴近身体后拉，把力量集中在背阔肌",
            "离心阶段让肩胛自然前伸，充分拉伸背部再进入下一次",
        ],
        "good_points": [
            "肩胛骨后缩到位，顶峰有挤压感",
            "动作节奏控制良好，没有明显的惯性甩动",
        ],
    },
    "_default": {
        "issues": [
            "核心未充分收紧，躯干稳定性不足",
            "动作幅度偏小，未达到完整活动范围",
            "离心阶段速度偏快，肌肉张力丢失",
            "双侧发力不对称，存在代偿现象",
        ],
        "suggestions": [
            "动作全程保持核心收紧，必要时先做核心激活练习",
            "降低重量，把动作幅度做完整再逐步加量",
            "离心阶段放慢到 2-3 秒，感受目标肌肉的控制",
            "面对镜子或录像自查，纠正两侧不对称",
        ],
        "good_points": [
            "整体动作节奏平稳，未见明显借力",
            "关节轨迹基本合理，降低受伤风险",
        ],
    },
}

#: 用户问题里出现这些词时，追加就医提醒（对应 RAG_SYSTEM_PROMPT 规则 3）
MEDICAL_KEYWORDS = ("痛", "疼", "伤", "受伤", "损伤", "康复", "劳损", "扭")


def score_level(score: int) -> str:
    """分数 → 评级（规范 7.3：优秀>=90 / 良好70-89 / 一般50-69 / 需改进<50）。"""
    if score >= 90:
        return "优秀"
    if score >= 70:
        return "良好"
    if score >= 50:
        return "一般"
    return "需改进"


def _pose_library(action_name: str) -> Dict[str, List[str]]:
    """按动作名匹配评估素材（找不到则用通用素材）。"""
    for keyword in POSE_EVALUATION_LIBRARY:
        if keyword != "_default" and keyword in action_name:
            return POSE_EVALUATION_LIBRARY[keyword]
    return POSE_EVALUATION_LIBRARY["_default"]


def _decode_pose_image(image_base64: str) -> bytes:
    """解码并校验姿态评估的入参图片（防御性校验，规范第 7 条）。

    Java 侧已在转 Base64 **之前**把图片压缩到 ≤1MB，这里是 Python 侧的兜底：
    内网接口（``/agent/v1/pose-evaluate``）仍可能被脚本/联调工具直接调用，
    一个 20MB 的图转 Base64 后约 27MB，``b64decode`` 会一次性申请这块内存，
    几个并发就足以把进程打爆。

    校验两件事：
    1. Base64 必须能正常解码（``validate=True``，拒绝非法字符与错误填充）；
    2. 解码后字节数 ≤ ``settings.pose_max_decoded_image_bytes``（默认 1.5MB）。

    :raises AgentInputError: 任一项不通过（统一走 HTTP 400 / Java 侧 9003 参数错误）
    """
    raw = str(image_base64).strip()

    try:
        decoded = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise AgentInputError(f"图片 Base64 内容非法，无法解码：{exc}") from exc

    max_bytes = settings.pose_max_decoded_image_bytes
    if len(decoded) > max_bytes:
        raise AgentInputError(
            f"图片过大（解码后 {len(decoded)} 字节，上限 {max_bytes} 字节），"
            f"请压缩后再上传"
        )
    return decoded


def evaluate_pose(image_base64: str, action_name: str) -> PoseEvaluateResponse:
    """动作姿态评估（第 6 周：真实多模态，规范 7.3）。

    链路：入口做防御性体积校验 → 通义千问 VL 看图 → 严格 JSON → 本地校验 + 派生 score_level。

    为什么要做入口体积校验：Java 侧已把图片压到 ≤1MB 再转 Base64，
    但内网接口仍可能被直接调用，必须防止超大 Base64 把 Python 进程内存打爆。

    **失败时如实报错、不编造分数**：多模态不可用（未配 Key / 网络异常 / 返回非 JSON）
    会抛 :class:`app.multimodal.MultimodalError`，Java 侧按规范第十一章第 7 条返回兜底文案
    「姿态评估服务暂时不可用，请稍后再试」。只有 ``MOCK_MODE=true`` 才走本地模拟打分。

    :raises AgentInputError: 图片内容为空 / Base64 非法 / 解码后超过
        ``settings.pose_max_decoded_image_bytes``（HTTP 400，Java 侧对应 9003 参数错误）
    """
    if not image_base64 or not str(image_base64).strip():
        raise AgentInputError("图片文件不能为空")

    image_bytes = _decode_pose_image(image_base64)
    action = str(action_name or "").strip() or "未指定动作"

    # MOCK_MODE=true：不调用多模态模型，用第 4 周的本地确定性实现（离线演示路径）
    if settings.mock_mode:
        logger.warning("MOCK_MODE=true：姿态评估走本地模拟打分（非真实图像推理）")
        return _evaluate_pose_local(image_bytes, action, len(str(image_base64)))

    return _evaluate_pose_multimodal(str(image_base64), action, image_bytes)


def _evaluate_pose_multimodal(
    image_base64: str, action: str, image_bytes: bytes
) -> PoseEvaluateResponse:
    """真实多模态实现：通义千问 VL 看图 → JSON → 校验 → 组装响应。

    ``score_level`` 一律由 ``score`` 本地派生（不采信模型自报的等级）：
    「分数与等级自洽」这条不变量由代码保证，而不是指望模型每次算对。
    """
    from .multimodal import MultimodalError, MultimodalInputError, get_multimodal

    client = get_multimodal()
    if not client.configured:
        raise MultimodalError("未配置 DASHSCOPE_API_KEY，无法调用多模态模型做姿态评估")

    started = time.monotonic()
    try:
        text = client.describe_image(
            image_base64,
            POSE_VL_USER_PROMPT.format(action=action),
            system_prompt=POSE_VL_SYSTEM_PROMPT.format(action=action),
            image_bytes=image_bytes,
            temperature=0.2,        # 姿态评估要的是稳定结论，不是创造力
            max_tokens=800,
        )
    except MultimodalInputError as exc:
        # 模型直接拒绝了这张图（如宽高 ≤10px、格式不被支持）——这是**入参问题**，
        # 不是服务故障。与下面「模型说看不出动作」走同一条路（HTTP 400 → Java 9003），
        # 用户看到的是「请换一张照片」而不是「服务暂时不可用，请稍后再试」。
        logger.warning("姿态评估入参被模型拒绝: %s", exc)
        raise AgentInputError(
            "照片无法用于姿态评估（可能尺寸过小或格式不受支持），"
            "请上传一张能看清全身、且包含完整动作过程的清晰照片"
        ) from exc

    payload = extract_json_object(text)
    if not payload:
        raise MultimodalError(f"姿态评估返回的不是 JSON: {truncate(text, 200)}")

    score = _pose_score(payload.get("score"))
    issues = _pose_text_list(payload.get("issues"))
    suggestions = _pose_text_list(payload.get("suggestions"))
    good_points = _pose_text_list(payload.get("good_points"))

    if issues and suggestions and len(issues) != len(suggestions):
        # 不阻断（模型偶尔漏一条），但留痕：Prompt 明确要求 issues 与 suggestions 等长
        logger.warning("姿态评估的问题数(%d)与建议数(%d)不一致，已按原样返回",
                       len(issues), len(suggestions))

    # 模型说「照片看不出动作」时，score=0 并不是真实的评估结果：
    # 直接返回会被前端渲染成 0 分 /「需改进」，与 issues 里的「无法判断」自相矛盾，
    # 用户还会以为是自己的动作有问题。按入参问题处理（HTTP 400 → Java 9003），
    # 明确引导用户换一张能看清动作的照片。
    if score == 0 and _looks_unjudgeable(issues):
        raise AgentInputError(
            "照片无法判断动作姿态，请上传一张能看清全身、且包含完整动作过程的清晰照片"
        )

    if not issues and not good_points:
        raise MultimodalError("姿态评估返回的内容为空（既没有问题也没有优点）")

    level = score_level(score)
    logger.info(
        "姿态评估完成(VL %s): action=%s imageBytes=%d score=%d level=%s 耗时=%.0fms",
        client.model,
        action,
        len(image_bytes),
        score,
        level,
        (time.monotonic() - started) * 1000,
    )
    return PoseEvaluateResponse(
        score=score,
        score_level=level,
        issues=issues,
        suggestions=suggestions,
        good_points=good_points,
        evaluated_at=now_local(),
        data_source="qwen_vl",  # 真实多模态推理，如实标注来源
    )


def _pose_score(value: Any) -> int:
    """把模型给的分数规范成 0-100 的整数。

    模型常犯的小毛病在这儿统一兜掉：``"78分"``、``78.0``、``" 78 "``、越界的 ``105``。
    完全拿不到数字时抛 :class:`app.multimodal.MultimodalError` —— 宁可如实报错，
    也不能凭空编一个分数（那会让用户以为照片真的被评估过）。
    """
    from .multimodal import MultimodalError

    if isinstance(value, bool):     # bool 是 int 的子类，先挡掉以免 True 被当成 1 分
        raise MultimodalError("姿态评估返回的 score 非法：布尔值")

    number: Optional[float] = None
    if isinstance(value, (int, float)):
        number = float(value)
    elif value is not None:
        match = re.search(r"-?\d+(?:\.\d+)?", str(value))
        if match:
            number = float(match.group())

    if number is None:
        raise MultimodalError(f"姿态评估未返回可用的 score: {truncate(str(value), 50)}")

    score = int(round(number))
    if score < 0 or score > 100:
        logger.warning("姿态评估返回的 score 越界(%s)，已夹到 0-100", value)
        score = max(0, min(100, score))
    return score


#: 模型明确表示「这张照片看不出动作」时会出现的关键词。
#: 命中且 score=0 时按入参问题处理，不把 0 分当成真实评估结果（见 evaluate_pose）。
_POSE_UNJUDGEABLE_KEYWORDS = (
    "无法判断", "无法评估", "不能判断", "无法识别", "看不清", "不清晰", "不是该动作", "与动作不符",
)


def _looks_unjudgeable(issues: List[str]) -> bool:
    """判断模型是否在说「照片不足以判断姿态」。"""
    return any(
        keyword in text
        for text in issues
        for keyword in _POSE_UNJUDGEABLE_KEYWORDS
    )


def _pose_text_list(value: Any, limit: int = 5) -> List[str]:
    """把模型给的字段规范成字符串列表（单个字符串 → 单元素列表；其它类型忽略）。"""
    if value is None:
        return []
    if isinstance(value, str):
        text = value.strip()
        return [text] if text else []
    if isinstance(value, list):
        return [
            str(item).strip()
            for item in value
            if isinstance(item, (str, int, float)) and str(item).strip()
        ][:limit]
    return []


def _evaluate_pose_local(
    image_bytes: bytes, action: str, base64_length: int
) -> PoseEvaluateResponse:
    """第 4 周的本地确定性实现（现仅在 ``MOCK_MODE=true`` 时使用）。

    分数由「动作名 + 图片长度 + 图片前 64 字符」的 SHA256 稳定派生
    （同一张图同一动作结果可复现），素材取自 :data:`POSE_EVALUATION_LIBRARY`。
    这**不是**真实的图像推理，只为无 Key 环境提供可演示的确定性输出。

    ⚠️ 返回结果的 ``data_source`` 固定为 ``mock_local``：这个分数与真实多模态推理
    在响应结构上完全一致，若不标注，调用方（Java/前端）会把编造的数字当成真实评估结果
    展示给用户 —— 那既是产品问题，也是诚信问题。
    """
    digest = hashlib.sha256(
        f"{action}|{base64_length}|{str(image_bytes[:64])}".encode("utf-8")
    ).hexdigest()
    score = 45 + int(digest[:8], 16) % 51  # 45 - 95
    level = score_level(score)

    library = _pose_library(action)
    issue_count = 4 if score < 50 else 3 if score < 70 else 2 if score < 90 else 1
    good_count = 1 if score < 50 else 2 if score < 90 else 3

    logger.warning(
        "姿态评估完成(Mock 模拟打分，非图像推理): action=%s imageBase64Length=%d "
        "imageBytes=%d score=%d level=%s",
        action,
        base64_length,
        len(image_bytes),
        score,
        level,
    )
    return PoseEvaluateResponse(
        score=score,
        score_level=level,
        issues=library["issues"][:issue_count],
        suggestions=library["suggestions"][:issue_count],
        good_points=library["good_points"][:good_count],
        evaluated_at=now_local(),
        data_source="mock_local",
    )


# ============================================================
# 四、知识库 RAG 问答
# ============================================================

#: Mock 知识库（第 5-6 周会被 data/seed_knowledge.json + Milvus 取代）。
#: keywords 仅用于 Mock 阶段的「假检索」打分，不进入 Milvus。
_MOCK_KNOWLEDGE_RAW: List[Dict[str, Any]] = [
    {
        "category": "动作要领",
        "title": "深蹲时膝盖与脚尖的位置关系",
        "keywords": ["深蹲", "膝盖", "脚尖", "超过脚尖"],
        "source": "《运动解剖学》第3版 · 下肢生物力学章节",
        "content": "膝盖是否超过脚尖取决于身体比例和深蹲方式，股骨较长的人在全蹲时膝盖自然超过脚尖，这是正常且安全的。真正需要关注的三点是：重心稳定在脚掌中部、脊柱保持中立、膝盖方向与脚尖一致。刻意把膝盖锁在脚尖后方反而会让躯干过度前倾、增加腰部剪切力。若膝前疼痛持续存在，建议先排查踝关节活动度与髋关节控制能力。",
    },
    {
        "category": "恢复与伤病",
        "title": "深蹲导致膝盖疼痛的常见原因",
        "keywords": ["深蹲", "膝盖", "疼痛", "十字韧带", "髌骨"],
        "source": "NSCA 力量训练指南 · 损伤预防章节",
        "content": "深蹲后膝前痛最常见的原因不是膝盖超过脚尖，而是膝盖内扣（动态外翻）、下蹲速度过快失去控制、突然大幅增加训练容量，以及踝关节背屈受限导致的代偿。处理顺序是：先降低重量与幅度，用无痛范围内的箱式深蹲重建动作模式，同时加强臀中肌与胫骨前肌力量。若疼痛伴随肿胀或关节不稳，应立即停止训练并就医。",
    },
    {
        "category": "动作要领",
        "title": "高杠深蹲与低杠深蹲的技术区别",
        "keywords": ["深蹲", "高杠", "低杠", "杠位"],
        "source": "Starting Strength · 基础杠铃训练",
        "content": "高杠深蹲杠铃置于斜方肌上部，躯干更直立、膝角参与更多，更适合股四头肌为主导的健美式训练；低杠深蹲杠铃置于肩胛冈下方，需要更大的髋部铰链与躯干前倾，能使用更大重量且对后链刺激更强。两者没有优劣，关键是选一种长期坚持并保持技术一致，不要在同一次训练里来回切换。",
    },
    {
        "category": "动作要领",
        "title": "卧推时肩胛骨稳定与肩部代偿",
        "keywords": ["卧推", "肩胛骨", "肩", "代偿", "肩痛"],
        "source": "《力量训练解剖学》 · 上肢推类动作",
        "content": "卧推前必须先沉肩、后缩肩胛骨并锁在凳面上，形成稳定的支撑平台，否则肩关节会在不稳定的状态下承受负荷，容易出现肩峰撞击与肩前侧疼痛。落点应控制在胸骨中下部，前臂在最低点垂直地面；肘部与躯干保持45-60度夹角，过大的外展角度会显著增加肩关节剪切力。",
    },
    {
        "category": "动作要领",
        "title": "硬拉的脊柱中立位与起始姿势",
        "keywords": ["硬拉", "脊柱", "腰", "起始姿势", "锁背"],
        "source": "NSCA 力量训练指南 · 硬拉技术标准",
        "content": "硬拉的安全前提是每一次起拉前都完成「锁背」：挺胸、收紧背阔肌把杠铃「拉向」身体、脊柱保持中立。杠铃应贴着小腿垂直上下，肩关节位于杠铃正上方，髋膝同步伸展完成锁定。常见错误包括起始位下背弯曲、杠铃离身体过远、锁定阶段过度后仰，这三个错误都会把负荷从腿部转移到腰椎。",
    },
    {
        "category": "动作要领",
        "title": "核心收紧的正确发力方式",
        "keywords": ["核心", "收紧", "腹横肌", "呼吸", "瓦氏"],
        "source": "《运动解剖学》第3版 · 核心稳定章节",
        "content": "真正的核心收紧不是「吸肚子」，而是让腹横肌像腰带一样向内加压、同时保持正常呼吸或在大重量时使用瓦氏呼吸。自测方法是咳嗽的瞬间感受腹部与腰部同时鼓起的张力。有效训练包括死虫式、平板支撑与农夫行走，这些都是「抗伸展、抗旋转」的动作模式，比单纯做卷腹更贴近实际训练需求。",
    },
    {
        "category": "营养饮食",
        "title": "增肌期蛋白质摄入量与分配",
        "keywords": ["蛋白质", "增肌", "摄入量", "吃多少"],
        "source": "ISSN 营养共识声明 · 蛋白质与运动表现",
        "content": "增肌期建议每日蛋白质摄入量 1.6-2.2 克/公斤体重，超过 2.2 克并不会带来额外的肌肉增长收益。关键是分配到 3-5 餐、每餐 0.3-0.4 克/公斤体重（约 20-40 克），因为单次摄入过多无法被更高效地利用。优先选择鸡蛋、鸡胸、瘦牛肉、鱼虾、乳制品与豆制品等完整蛋白来源。",
    },
    {
        "category": "营养饮食",
        "title": "训练前后碳水与能量补充策略",
        "keywords": ["碳水", "训练前", "训练后", "补充", "能量", "加餐"],
        "source": "《运动营养学》 · 训练期营养时机",
        "content": "训练前 1-2 小时补充 1-2 克/公斤体重的碳水（如米饭、燕麦、香蕉）可以显著提高训练容量与力量表现；训练后 2 小时内摄入碳水加蛋白质能加快糖原恢复与肌肉修复。若训练时间在早上且空腹训练容易头晕，可以提前 30 分钟吃一根香蕉或一片面包。总热量与总蛋白达标永远比进食时机更重要。",
    },
    {
        "category": "营养饮食",
        "title": "减脂期的热量缺口如何设置",
        "keywords": ["减脂", "热量", "缺口", "减肥", "掉秤"],
        "source": "《运动营养学》 · 体重管理章节",
        "content": "减脂期的合理热量缺口是每日总消耗的 10%-20%，对应每周体重下降约 0.5%-1%。缺口过大（超过 25%）会显著增加肌肉流失、训练表现下降与代谢适应。建议保证蛋白质不低于 1.8 克/公斤体重、抗阻训练频率不下降，并每 2-4 周根据体重变化趋势回调热量，而不是每天盯着体重秤上的单日波动。",
    },
    {
        "category": "恢复与伤病",
        "title": "延迟性肌肉酸痛的成因与处理",
        "keywords": ["酸痛", "doms", "延迟性", "恢复", "肌肉疼"],
        "source": "ACSM 运动医学与健康期刊 · 运动后恢复",
        "content": "延迟性肌肉酸痛通常在训练后 24-72 小时达到峰值，主要来自不习惯的动作模式或离心负荷过大造成的肌纤维微损伤，并非乳酸堆积（乳酸在训练后 1 小时内即被代谢）。处理方式包括轻度有氧促进血流、充足睡眠、保证蛋白质与热量摄入、局部热敷放松。酸痛期间可以训练其他肌群，不必完全停练。",
    },
    {
        "category": "恢复与伤病",
        "title": "睡眠与训练恢复的关系",
        "keywords": ["睡眠", "恢复", "休息", "熬夜"],
        "source": "《睡眠医学评论》 · 睡眠与运动表现",
        "content": "睡眠是合成代谢激素分泌与神经肌肉恢复的核心窗口，连续睡眠不足 6 小时会明显降低力量表现、增加主观疲劳感与受伤风险。建议保证 7-9 小时睡眠，固定入睡与起床时间；训练后避免立即摄入咖啡因，睡前 1 小时远离强光屏幕。睡眠质量差时，降低训练容量比硬顶更有利于长期进步。",
    },
    {
        "category": "恢复与伤病",
        "title": "过度训练的信号与减载周安排",
        "keywords": ["过度训练", "减载", "疲劳", "练不动", "平台期"],
        "source": "NSCA 力量训练指南 · 疲劳管理",
        "content": "过度训练的早期信号包括：静息心率升高、相同重量 RPE 明显上升、训练欲望下降、睡眠变差、关节持续酸痛。出现 2 项以上就应安排减载周：把训练容量降到平时的 40%-60%（保持重量、减少组数），或干脆休息 3-5 天。每 4-8 周主动安排一次减载，通常比被动崩掉后再恢复更高效。",
    },
    {
        "category": "训练计划",
        "title": "增肌训练的分化方式选择（推拉腿 / 上下肢）",
        "keywords": ["分化", "计划", "推拉腿", "上下肢", "怎么安排"],
        "source": "《力量训练解剖学》 · 训练计划设计",
        "content": "每周训练 3 天建议用全身分化（每次覆盖胸背腿核心），4 天用上下肢分化，5-6 天用推拉腿分化。推拉腿的核心优势是同一肌群有 48-72 小时恢复窗口，且每次训练的注意力更集中。分化方式本身没有绝对优劣，选择你能长期坚持、且每个肌群每周能练到 10-20 个有效组的方案即可。",
    },
    {
        "category": "训练计划",
        "title": "渐进超负荷的四种实现方式",
        "keywords": ["渐进", "超负荷", "加重", "进步", "平台"],
        "source": "NSCA 力量训练指南 · 训练变量",
        "content": "渐进超负荷不只有「加重量」一种实现方式：一是增加重量，二是增加次数或组数，三是缩短组间休息，四是提高动作质量（更完整的幅度、更慢的离心）。当重量长期卡住时，先尝试在相同重量下多完成 1-2 次，或者增加 1 组，同样能带来进步。记录训练日志是执行渐进超负荷的前提。",
    },
    {
        "category": "训练计划",
        "title": "每组训练次数与重量的选择（力量 vs 围度）",
        "keywords": ["次数", "重量", "组数", "力量", "围度", "rm"],
        "source": "《力量训练解剖学》 · 训练变量",
        "content": "以提升最大力量为目标时选择 1-5 次/组（85% 以上 1RM），组间休息 3-5 分钟；以增加肌肉围度为目标时选择 6-12 次/组（67%-85% 1RM），组间休息 60-90 秒；以肌耐力为目标时选择 15 次以上并缩短休息。无论哪个区间，接近力竭（保留 1-2 次余力）才是增肌的关键变量，重量区间只是工具。",
    },
    {
        "category": "补剂科普",
        "title": "肌酸的作用、剂量与安全性",
        "keywords": ["肌酸", "creatine", "补剂", "安全"],
        "source": "ISSN 营养共识声明 · 肌酸补充",
        "content": "一水肌酸是目前证据最充分的运动补剂，能提高高强度、短时间的爆发力表现并间接支持增肌。常规用法是每天 3-5 克长期服用，无需冲击期，服用时间不影响效果，与碳水同服吸收略好。健康人群长期使用安全性良好，可能出现轻微水潴留（体重上升 0.5-1 公斤属正常）；肾功能异常者需先咨询医生。",
    },
    {
        "category": "补剂科普",
        "title": "蛋白粉与乳清蛋白的成分类比",
        "keywords": ["蛋白粉", "乳清", "whey", "补剂", "喝蛋白粉"],
        "source": "ISSN 营养共识声明 · 蛋白质补充",
        "content": "乳清蛋白粉本质上是把牛奶中的蛋白质提纯后做成的方便食品，其氨基酸构成完整、吸收快，是性价比很高的蛋白质来源，但没有任何「超越食物的魔法」。选择时优先看每份蛋白质含量（一般 20-25 克）与配料表简洁度；乳糖不耐受者可选分离乳清或水解乳清。蛋白粉解决的是「吃不够蛋白质」的问题，不能替代正常饮食。",
    },
    {
        "category": "补剂科普",
        "title": "BCAA 支链氨基酸是否必要",
        "keywords": ["bcaa", "支链", "氨基酸", "补剂", "必要"],
        "source": "ISSN 营养共识声明 · 氨基酸补充",
        "content": "对于每日蛋白质摄入已达标的训练者，额外补充 BCAA 基本没有额外收益，因为完整蛋白中已包含足量亮氨酸、异亮氨酸与缬氨酸。BCAA 可能有价值的场景仅包括：空腹训练时减少肌肉分解、蛋白质摄入长期不足。把预算用在优质蛋白食物或一水肌酸上，收益通常更明确。",
    },
]


def _validate_mock_knowledge(raw: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """用规范的 KnowledgeEntry 模型校验 Mock 知识条目。

    既保证 Mock 数据质量（content ≥50 字、title ≤200 字等），也顺带验证该模型可用 ——
    第 5-6 周导入 seed_knowledge.json 时用的是同一个模型。
    """
    for item in raw:
        KnowledgeEntry(
            category=item["category"],
            title=item["title"],
            content=item["content"],
            source=item["source"],
        )
    return raw


MOCK_KNOWLEDGE: List[Dict[str, Any]] = _validate_mock_knowledge(_MOCK_KNOWLEDGE_RAW)

#: 知识分类（规范固定 5 类）
KNOWLEDGE_CATEGORIES = ("动作要领", "营养饮食", "恢复与伤病", "训练计划", "补剂科普")

#: 分类别名
CATEGORY_ALIASES: Dict[str, str] = {
    "动作要领": "动作要领", "动作": "动作要领", "技术": "动作要领", "姿势": "动作要领",
    "营养饮食": "营养饮食", "营养": "营养饮食", "饮食": "营养饮食", "吃": "营养饮食",
    "恢复与伤病": "恢复与伤病", "恢复": "恢复与伤病", "伤病": "恢复与伤病", "损伤": "恢复与伤病",
    "训练计划": "训练计划", "计划": "训练计划", "训练": "训练计划",
    "补剂科普": "补剂科普", "补剂": "补剂科普", "营养补剂": "补剂科普",
}

#: 分类 → 收尾建议（Mock 回答的最后一句）
CATEGORY_TIPS: Dict[str, str] = {
    "动作要领": "先在热身组把动作模式做对再上重量；如果条件允许，让教练现场看 1-2 次动作，比看十个视频都有效。",
    "营养饮食": "先把总热量和蛋白质吃够，再考虑精细化调整；连续记录 1-2 周饮食后再做判断，不要凭单日数据下结论。",
    "恢复与伤病": "恢复和训练同等重要：保证 7-8 小时睡眠，同一肌群训练间隔 48 小时以上，出现持续疼痛请及时就医。",
    "训练计划": "计划不要频繁更换，同一方案坚持 6-8 周，用训练日志判断是否真的在进步。",
    "补剂科普": "补剂只是「锦上添花」：把训练、饮食、睡眠做到位，补剂才可能发挥效果。",
}

#: 中文二元组（Mock 检索用：无分词器时用字符二元组估算相关度）
_PUNCTUATION = set("，。！？、；：“”‘’（）《》【】,.!?;:\"'()<>[]{}\n\r\t -—…·~`")


def _bigrams(text: str) -> set:
    """提取字符二元组并去停用标点（用于估算相关度）。"""
    cleaned = [ch.lower() for ch in str(text or "") if ch not in _PUNCTUATION]
    return {cleaned[i] + cleaned[i + 1] for i in range(len(cleaned) - 1)}


def _score_entry(question: str, entry: Dict[str, Any]) -> float:
    """Mock 相关度打分（0.00-0.97）。

    第 5-6 周会被「Embedding 余弦相似度」取代；本阶段用
    「关键词命中 + 字符二元组重合度」模拟，保证分数看起来合理且稳定可复现。
    """
    question_lower = str(question or "").lower()
    hits = sum(1 for kw in entry.get("keywords", []) if kw.lower() in question_lower)
    question_grams = _bigrams(question)
    text_grams = _bigrams(entry["title"] + entry["content"])
    overlap = (
        len(question_grams & text_grams) / len(question_grams) if question_grams else 0.0
    )
    score = 0.62 + 0.08 * hits + 0.15 * overlap
    return round(min(0.97, score), 2)


def _normalize_category(category: Optional[str]) -> Optional[str]:
    """知识分类归一化，无法识别返回 None。"""
    text = str(category or "").strip().lower()
    if not text:
        return None
    if text in CATEGORY_ALIASES:
        return CATEGORY_ALIASES[text]
    for name in KNOWLEDGE_CATEGORIES:
        if name in text:
            return name
    for alias, standard in CATEGORY_ALIASES.items():
        if alias in text:
            return standard
    return None


def _needs_medical_note(question: str) -> bool:
    return any(keyword in str(question or "") for keyword in MEDICAL_KEYWORDS)


def _build_rag_answer(
    question: str,
    ranked: List[Tuple[float, Dict[str, Any]]],
    relevance_threshold: float = 0.75,
) -> str:
    """把检索结果拼成 Markdown 回答（结构对齐 RAG_SYSTEM_PROMPT 的输出要求）。

    :param relevance_threshold: 「相关命中」的余弦阈值。必须由调用方按 Embedding
        provider 传入 —— 真实语义模型用 0.75，字符哈希兜底实现用其校准值 0.15，
        否则会一直误报「未检索到高度相关」。
    """
    best_score, best = ranked[0]
    lines: List[str] = [f"## 🏋️ {question}", ""]
    if best_score < relevance_threshold:
        lines += [
            "> ⚠️ 知识库中未检索到高度相关的资料，以下回答基于通用健身专业知识，仅供参考。",
            "",
        ]
    lines += [f"**简短回答**：{first_sentences(best['content'], 1)}", "", "### 📖 科学解释", best["content"], ""]

    points: List[str] = []
    for sentence in [
        s.strip() for s in best["content"].replace("\n", "。").split("。") if s.strip()
    ][1:4]:
        points.append(sentence + "。")
    for _, entry in ranked[1:]:
        if len(points) >= 3:
            break
        head = first_sentences(entry["content"], 1)
        if head and head not in points:
            points.append(head)
    if points:
        lines += ["### ⚠️ 关键要点"]
        for index, point in enumerate(points[:3], start=1):
            lines.append(f"{index}. {point}")
        lines.append("")

    lines += ["### 📚 参考来源"]
    for _, entry in ranked:
        source = str(entry["source"]).strip()
        # 来源本身可能已带书名号（《运动解剖学》第3版…），避免出现《《…》》
        citation = source if source.startswith("《") else f"《{source}》"
        lines.append(f"> 📚 参考：{citation}")

    if _needs_medical_note(question):
        lines += [
            "",
            "> 🩺 你的问题涉及疼痛或伤病，个体差异很大，**建议咨询专业医生或康复教练**后再恢复相关训练。",
        ]
    lines += ["", f"> 💡 **建议**：{CATEGORY_TIPS.get(best['category'], CATEGORY_TIPS['动作要领'])}"]
    return "\n".join(lines)


def chat_with_rag(
    question: str, category: Optional[str] = None, user_id: Optional[int] = None
) -> ChatResponse:
    """健身知识库 RAG 问答（规范 7.4）。

    **三层降级**，任何一层可用都不会让调用方拿到 500：

    1. **完整 RAG**：Embedding → Milvus Top-5 检索 → DeepSeek 基于 Context 生成
    2. **检索结果 + 本地拼装**（Milvus 可用但无 LLM Key 或 MOCK_MODE=true）：
       保留真实检索来源，只是回答未经大模型润色
    3. **内置知识库**（检索也不可用）：用项目内置 18 条知识做词法检索

    ⚠️ 走第 3 层时**必须**让调用方看出来：该层的 ``sources[].score`` 是启发式合成值
    （不是余弦相似度），来源也不是 200 条真实知识库。因此返回值里带上
    ``data_source`` / ``degraded`` / ``degradation_reason`` / ``sources[].score_type``
    四个标记 —— 少了它们，调用方会把兜底结果当成真实 RAG 结果展示。

    :raises AgentInputError: 问题为空（HTTP 400）
    """
    text = str(question or "").strip()
    if not text:
        raise AgentInputError("问题不能为空")

    # --- 第 1、2 层：真实链路 ---
    try:
        return _chat_with_real_rag(text, category, user_id)
    except Exception as exc:  # noqa: BLE001 - 降级是本函数的职责
        logger.warning(
            "真实 RAG 链路不可用，降级到内置知识库: %s: %s", type(exc).__name__, exc
        )

    # --- 第 3 层：离线兜底 ---
    return _chat_with_mock_knowledge(text, category, user_id)


def _retrieval_threshold() -> float:
    """取当前 Embedding provider 校准过的相关性阈值。

    取不到时回退 0.75（真实语义模型的典型阈值），保证异常时行为可预期。
    """
    try:
        from .embedding import get_embedding

        return float(get_embedding().relevance_threshold)
    except Exception:  # noqa: BLE001 - 阈值只是提示文案开关，取不到不该影响主流程
        return 0.75


def _chat_with_real_rag(
    question: str, category: Optional[str], user_id: Optional[int]
) -> ChatResponse:
    """真实 RAG 链路：Milvus 检索 + DeepSeek 生成。

    检索失败但 LLM 可用时，按规范降级为「纯 LLM 回答」并标注知识库不可用。
    """
    from .llm import LLMError, get_llm
    from .rag import DEGRADED_NOTICE, get_rag_engine

    llm = get_llm()
    engine = get_rag_engine()

    hits: List[Any] = []
    context = ""
    degraded = False
    try:
        hits, context = engine.retrieve_with_context(question, category=category)
    except Exception as exc:  # noqa: BLE001 - 检索失败要降级而不是中断
        logger.warning("知识库检索失败，降级为纯 LLM 回答: %s", exc)
        degraded = True

    if not hits and not degraded:
        # 检索成功但没命中任何条目：同样按降级处理
        logger.info("知识库检索无命中，降级为纯 LLM 回答: question=%s", question[:30])
        degraded = True

    if not llm.configured or settings.mock_mode:
        # 没有 LLM Key（或 MOCK_MODE=true）时**不要**退回内置的 18 条 Mock 知识 ——
        # 那等于白白浪费已经检索到的 200 条真实知识。
        # 改为：用真实检索结果 + 本地拼装回答，并明确告知用户这份回答未经大模型润色。
        if not hits:
            raise LLMError("未配置 DEEPSEEK_API_KEY（或 MOCK_MODE=true），且知识库无命中，无法生成回答")

        reason = "MOCK_MODE=true" if settings.mock_mode else "未配置 DEEPSEEK_API_KEY"
        logger.info(
            "%s：使用真实检索结果 + 本地拼装回答（来源 %d 条）", reason, len(hits)
        )
        ranked = [(hit.score, {
            "category": hit.category,
            "title": hit.title,
            "content": hit.content,
            "source": hit.source,
        }) for hit in hits]
        answer = _build_rag_answer(question, ranked, _retrieval_threshold())
        answer += (
            "\n\n> ℹ️ 本回答由知识库检索结果直接拼装生成（未配置大模型 Key，"
            "未经 AI 润色）。配置 `DEEPSEEK_API_KEY` 后即为真实 AI 生成。"
        )
        return ChatResponse(
            question=question,
            answer=answer,
            sources=[
                ChatSource(
                    category=hit.category,
                    title=hit.title,
                    content=hit.content,
                    score=round(float(hit.score), 4),
                    score_type="cosine",  # 来源仍是真实 Milvus 检索，分数是余弦相似度
                )
                for hit in hits
            ],
            generated_at=now_local(),
            # 来源是真实知识库，但回答未经大模型润色 —— 两者分开标注，避免误读
            data_source="milvus",
            degraded=True,
            degradation_reason=f"{reason}：已用真实检索结果本地拼装回答，未经大模型润色",
        )

    # ---- 生成回答 ----
    if degraded:
        system_prompt = (
            RAG_SYSTEM_PROMPT.replace("【参考资料】\n{context}", "【参考资料】\n（无）")
            + f"\n\n{DEGRADED_NOTICE}"
        )
    else:
        system_prompt = RAG_SYSTEM_PROMPT.format(context=context)

    answer = llm.chat_with_system(
        system_prompt,
        RAG_USER_PROMPT.format(question=question),
        temperature=0.3,   # 知识问答要稳，降低发散
        max_tokens=1200,
    )

    sources = [
        ChatSource(
            category=hit.category,
            title=hit.title,
            content=hit.content,
            score=round(float(hit.score), 4),
            score_type="cosine",
        )
        for hit in hits
    ]

    logger.info(
        "知识库问答完成(真实RAG): 来源=%d 条 降级=%s llm=%s",
        len(sources), degraded, llm.model,
    )
    return ChatResponse(
        question=question,
        answer=answer,
        sources=sources,
        generated_at=now_local(),
        # 检索失败/无命中时 hits 为空 → 来源实为「无」，如实标注成 none，
        # 避免前端把「没有来源」与「来源是知识库」混为一谈
        data_source="milvus" if sources else "none",
        degraded=degraded,
        degradation_reason=(
            "知识库检索失败或无命中，已退化为纯大模型回答（本条回答未使用知识库）"
            if degraded
            else None
        ),
    )


def _chat_with_mock_knowledge(
    text: str, category: Optional[str], user_id: Optional[int]
) -> ChatResponse:
    """离线兜底：用内置知识条目做词法检索并拼装回答（第 4 周已验证的实现）。"""
    logger.info(
        "知识库问答(内置兜底): userId=%s category=%s questionLength=%d",
        user_id,
        category,
        len(text),
    )

    candidates = MOCK_KNOWLEDGE
    normalized_category = _normalize_category(category)
    if category:
        filtered = (
            [e for e in MOCK_KNOWLEDGE if e["category"] == normalized_category]
            if normalized_category
            else []
        )
        if filtered:
            candidates = filtered
        else:
            logger.warning(
                "分类过滤未命中（category=%s），回退为全库检索", category
            )

    ranked = sorted(
        ((_score_entry(text, entry), entry) for entry in candidates),
        key=lambda item: (-item[0], item[1]["title"]),
    )
    top = ranked[:3]
    if len(top) < 2:  # 兜底：规范要求 2-3 条来源
        for entry in MOCK_KNOWLEDGE:
            if entry not in [item[1] for item in top]:
                top.append((_score_entry(text, entry), entry))
            if len(top) >= 2:
                break

    sources = [
        ChatSource(
            category=entry["category"],
            title=entry["title"],
            content=entry["content"],
            score=round(float(score), 2),
            # 这个分数由关键词命中数 + 字符二元组重合度合成（见 _score_entry），
            # **不是**向量余弦相似度，不同问题之间也不可比 —— 必须如实标注口径
            score_type="heuristic",
        )
        for score, entry in top
    ]
    answer = _build_rag_answer(text, top)
    # ⚠️ 这里**无条件**追加降级说明。
    # 原因：_build_rag_answer 只在 best_score < 阈值时才提示「未检索到高度相关的资料」，
    # 于是「命中的好」的问题会拿回一份看不出任何降级痕迹的回答 —— 却带着 0.97 这类
    # 看似余弦相似度的分数与「📚 参考」引用，与真实 RAG 回答无法区分。
    answer += (
        "\n\n> ⚠️ **本次回答来自内置知识条目，不是 Milvus 知识库检索结果**。"
        "知识库暂时不可用，上面的相关度为启发式估计值（非向量相似度），仅供参考。"
    )
    logger.warning(
        "知识库问答完成(内置兜底，非 Milvus 检索): 返回来源=%d 条 topScore=%.2f 分类=%s",
        len(sources),
        sources[0].score if sources else 0.0,
        normalized_category or "全部",
    )
    return ChatResponse(
        question=text,
        answer=answer,
        sources=sources,
        generated_at=now_local(),
        data_source="builtin",
        degraded=True,
        degradation_reason="知识库检索不可用，已退化为内置知识条目（相关度为启发式估计值）",
    )


# ============================================================
# 五、健康检查
# ============================================================

#: Mock 模式下的知识库统计（仅当 Milvus 真的不可用时才会用到）
MOCK_KNOWLEDGE_STATS: Dict[str, Any] = {
    "total_documents": 200,
    "last_updated": "2026-07-15 10:00:00",
    "index_type": "IVF_FLAT",
}


def get_knowledge_health() -> KnowledgeHealthResponse:
    """Milvus 知识库健康检查（规范 7.5）。

    **第 5 周起改为真实探测**：直接问 Milvus 要连接状态、文档数、索引类型与维度。

    这里刻意修掉了第 4 周的一个坑：当时 Mock 模式会**谎报**
    ``milvus_connected=true, total_documents=200``，看起来一切正常，
    实际上根本没连 Milvus。现在 Milvus 不可用就如实返回未连接，
    前端与 Java 侧不会再被误导。
    """
    try:
        from .rag import get_rag_engine  # 延迟导入：避免第 4 周路径被 Milvus 依赖牵连

        info = get_rag_engine().health()
        connected = bool(info.get("milvus_connected"))
        logger.info(
            "知识库健康检查: connected=%s documents=%s index=%s embedding=%s",
            connected,
            info.get("total_documents"),
            info.get("index_type"),
            (info.get("embedding") or {}).get("provider"),
        )
        return KnowledgeHealthResponse(
            milvus_connected=connected,
            collection_name=info.get("collection_name") or settings.milvus_collection,
            total_documents=info.get("total_documents"),
            last_updated=info.get("last_updated"),
            index_type=info.get("index_type"),
            embedding_dim=info.get("embedding_dim") or settings.embedding_dim,
        )
    except Exception as exc:  # noqa: BLE001 - 健康检查绝不能抛异常
        logger.warning("知识库健康检查失败（按未连接处理）: %s", exc)
        return KnowledgeHealthResponse(
            milvus_connected=False,
            collection_name=settings.milvus_collection,
            total_documents=None,
            last_updated=None,
            index_type=None,
            embedding_dim=settings.embedding_dim,
        )


def check_agent_health() -> HealthData:
    """Python AI 服务健康检查（规范 10.2）。

    ``knowledge_base_ready`` = 已连接 Milvus 且有文档；
    ``llm_api_configured`` = 真实读取 DEEPSEEK_API_KEY 是否存在（**不做 Mock**，
    未配置 Key 就是 false —— 诚实反映配置状态，Mock 模式只影响业务返回内容）。
    """
    knowledge = get_knowledge_health()
    ready = bool(
        knowledge.milvus_connected and (knowledge.total_documents or 0) > 0
    )
    data = HealthData(
        status="UP",
        milvus_connected=knowledge.milvus_connected,
        llm_api_configured=settings.llm_api_configured,
        knowledge_base_ready=ready,
        timestamp=now_iso(),
    )
    logger.info(
        "健康检查: status=%s milvusConnected=%s llmApiConfigured=%s knowledgeBaseReady=%s mockMode=%s",
        data.status,
        data.milvus_connected,
        data.llm_api_configured,
        data.knowledge_base_ready,
        settings.mock_mode,
    )
    return data


# ============================================================
# 六、周计划生成（第 7 周：RabbitMQ 消费者调用）
# ============================================================


def generate_weekly_plan(message: dict) -> str:
    """根据 MQ 消息里的本周数据生成下周训练建议（Markdown）。

    入参是 Java ``WeeklyPlanProducer`` 发到 ``ai.weekly.plan`` 的**原始消息体**
    （规范第 1059-1085 行的 MQ 消息体 Schema，camelCase）::

        {taskId, userId, weekStart, weekEnd, trainingSummary{trainingDays, totalActions,
         totalVolume, avgRpe, topActions[]}, bodyMetrics{startWeight, endWeight,
         weightChange}, dietSummary{avgDailyCalories, totalMeals}, timestamp}

    ## 为什么这个函数「绝不抛异常、绝不返回空串」

    它跑在 MQ 消费者里。抛异常 = 消息被 ``basic_nack(requeue=True)`` 重投，
    重投 3 次后进死信队列 —— 用户**永远拿不到本周周计划**。
    而「未配 Key / 大模型偶发故障 / 消息字段缺失」都是预期内的常态，
    不该让整条链路失败。因此这里的降级哲学与 :func:`generate_summary` 完全一致：

    - 配了 Key 且 ``MOCK_MODE=false`` → 用 ``WEEKLY_PLAN_SYSTEM_PROMPT`` 调 DeepSeek；
    - 未配 Key / ``MOCK_MODE=true`` / 调用失败 / 返回空 → 回落到本地拼装，
      数字全部由入参真实读取与计算（训练天数、总容量、平均 RPE、体重变化、日均热量），
      不做任何写死。

    :param message: MQ 消息体（dict）；字段缺失/类型不对时按 0/占位处理，不崩
    :return: Markdown 文本（保证非空）
    """
    # --- ① 解析入参（宽容解析：字段缺失、类型不对都不能让消费者崩掉）---
    data = message if isinstance(message, dict) else {}
    training = data.get("trainingSummary")
    training = training if isinstance(training, dict) else {}
    metrics = data.get("bodyMetrics")
    metrics = metrics if isinstance(metrics, dict) else {}
    diet = data.get("dietSummary")
    diet = diet if isinstance(diet, dict) else {}

    user_id = to_int(data.get("userId"))
    week_start = str(data.get("weekStart") or "").strip()
    week_end = str(data.get("weekEnd") or "").strip()

    training_days = to_int(training.get("trainingDays"))
    total_actions = to_int(training.get("totalActions"))
    total_volume = to_float(training.get("totalVolume"))
    avg_rpe = to_float(training.get("avgRpe"))

    raw_top = training.get("topActions")
    top_actions: List[Dict[str, Any]] = []
    if isinstance(raw_top, list):
        for item in raw_top[:5]:
            if not isinstance(item, dict):
                continue
            name = str(item.get("actionName") or item.get("action_name") or "").strip()
            if not name:
                continue
            top_actions.append(
                {
                    "name": name,
                    "count": to_int(item.get("count")),
                    "volume": to_float(item.get("totalVolume")),
                }
            )

    start_weight = to_float(metrics.get("startWeight"))
    end_weight = to_float(metrics.get("endWeight"))
    weight_change = to_float(metrics.get("weightChange"))
    avg_calories = to_float(diet.get("avgDailyCalories"))
    total_meals = to_int(diet.get("totalMeals"))

    # 周次（规范 8.1 的示例标题是「第32周训练复盘与下周计划」）；解析失败就退化成纯日期区间
    week_label = f"{week_start} ~ {week_end}" if week_start and week_end else (
        week_start or week_end or "本周"
    )
    week_no = ""
    try:
        from datetime import datetime as _datetime  # 局部导入，避免动到文件顶部的 import 区

        week_no = f"第{_datetime.strptime(week_start, '%Y-%m-%d').isocalendar()[1]}周"
    except Exception:  # noqa: BLE001 - 只是标题装饰，解析不出就不用
        week_no = ""

    # --- ② 优先走真实大模型（与 generate_summary 同一套降级哲学）---
    plan = ""
    mock_or_no_key = settings.mock_mode or not settings.llm_api_configured
    try:
        if mock_or_no_key:
            # 用异常表达「此路不通」，统一走下面的本地拼装，避免这里散落分支
            raise RuntimeError(
                "MOCK_MODE=true" if settings.mock_mode else "未配置 DEEPSEEK_API_KEY"
            )
        from .llm import get_llm

        llm = get_llm()
        if not llm.configured:
            raise RuntimeError("未配置 DEEPSEEK_API_KEY")

        import json as _json  # 局部导入，避免动到文件顶部的 import 区

        weekly_data = _json.dumps(data, ensure_ascii=False, indent=2)
        logger.info(
            "调用大模型生成周计划: userId=%s week=%s 训练天数=%s 动作数=%s 总容量=%s",
            user_id, week_label, training_days, total_actions, fmt_num(total_volume),
        )
        plan = str(
            llm.chat_with_system(
                WEEKLY_PLAN_SYSTEM_PROMPT,
                WEEKLY_PLAN_USER_PROMPT.format(weekly_data=weekly_data),
                # 建议要基于给定数据、别自由发挥
                temperature=0.4,
                max_tokens=1000,
            )
            or ""
        ).strip()
        if not plan:
            raise RuntimeError("大模型返回了空内容")
        logger.info(
            "周计划生成完成(LLM): userId=%s week=%s 字数=%d",
            user_id, week_label, len(plan),
        )
        return plan
    except Exception as exc:  # noqa: BLE001 - 降级是本函数的职责，绝不能让消息进死信
        logger.warning(
            "大模型生成周计划失败，回落本地拼装: %s: %s", type(exc).__name__, exc
        )

    # --- ③ 本地拼装兜底：结构完整、数字全部来自入参 ---
    lines: List[str] = []
    title = f"## 📅 {week_no}训练复盘与下周计划" if week_no else "## 📅 本周训练复盘与下周计划"
    lines += [title, "", f"> 周期：{week_label}（userId={user_id}）", ""]

    # 本周数据复盘
    lines.append("### 📊 本周数据复盘")
    if training_days or total_actions or total_volume > 0:
        rpe_text = f"，平均 RPE {fmt_num(avg_rpe)}/10" if avg_rpe > 0 else "（未记录 RPE）"
        lines.append(
            f"- 训练概况：本周训练 **{training_days}** 天，完成 **{total_actions}** 个动作，"
            f"总容量 **{fmt_num(total_volume)}kg**{rpe_text}"
        )
    else:
        # 空训练数据也要给出合理文案（而不是留白）
        lines.append(
            "- 训练概况：本周**没有可用的训练记录**，无法评估训练量与强度。"
            "建议下周先从每周 3 天、每次 4-6 个动作重新建立节奏，再逐步加量。"
        )

    if top_actions:
        detail = "；".join(
            f"{item['name']} {item['count']} 次 / {fmt_num(item['volume'])}kg"
            for item in top_actions
        )
        lines.append(f"- 重点动作：{detail}")

    if start_weight > 0 or end_weight > 0:
        trend = (
            "基本持平"
            if abs(weight_change) < 0.2
            else ("下降" if weight_change < 0 else "上升")
        )
        lines.append(
            f"- 体重变化：{fmt_num(start_weight)}kg → {fmt_num(end_weight)}kg，"
            f"变化 **{weight_change:+.1f}kg**（{trend}）"
        )
    else:
        lines.append("- 体重变化：本周未记录体重数据，建议固定晨起空腹称重的习惯")

    if avg_calories > 0:
        meals_text = f"，共 {total_meals} 餐" if total_meals else ""
        lines.append(f"- 饮食热量：日均摄入 **{fmt_num(avg_calories)}kcal**{meals_text}")
    else:
        lines.append("- 饮食热量：本周未记录饮食数据，无法评估热量是否与训练量匹配")

    # 下周训练调整建议（1-3 条，全部由上面的真实数字推导）
    advice: List[str] = []
    if top_actions:
        lead = top_actions[0]
        advice.append(
            f"以 **{lead['name']}** 为主项继续渐进超负荷：在动作质量不下降的前提下"
            "小幅加重（+2.5kg）或加 1 组，先把本周表现最好的动作做扎实"
        )
    if training_days >= 6:
        advice.append(
            f"本周训练 **{training_days}** 天、频率偏高，下周降到 4-5 天并固定 1-2 个完全休息日，"
            "给同一肌群留足 48 小时恢复窗口"
        )
    elif training_days > 0:
        advice.append(
            f"本周训练 **{training_days}** 天，下周可保持节奏并优先补齐薄弱肌群"
            "（每周每个肌群 10-20 个有效组为宜）"
        )
    else:
        advice.append(
            "下周先恢复训练习惯：每周 3 天全身训练（每次覆盖胸背腿核心），"
            "重量以「留 2-3 次余力」为准，避免一上来就练到力竭"
        )
    if avg_rpe >= 8.5:
        advice.append(
            f"本周平均 RPE 已达 **{fmt_num(avg_rpe)}/10**（接近力竭），"
            "下周建议整体降 10%-20% 容量或安排一个减载周，把状态重新拉回来"
        )
    elif 0 < avg_rpe <= 6:
        advice.append(
            f"本周平均 RPE 仅 **{fmt_num(avg_rpe)}/10**，强度偏轻松："
            "下周可小幅提升重量或增加 1 组，让训练更接近「留 1-2 次余力」"
        )
    if weight_change < -0.5:
        advice.append(
            f"体重本周下降 **{weight_change:+.1f}kg**：若目标是增肌，"
            f"建议日均热量从 {fmt_num(avg_calories)}kcal 上调 200-300kcal 并保证蛋白质 1.6-2.2g/kg；"
            "若目标是减脂，这个速度可接受，但每周降幅不宜超过体重的 1%"
        )
    elif weight_change > 0.5:
        advice.append(
            f"体重本周上升 **{weight_change:+.1f}kg**：先确认是肌肉增长还是水潴留/热量超标，"
            "连续记录 2-4 周趋势再决定是否调整热量"
        )
    if 0 < avg_calories < 1800:
        advice.append(
            f"日均摄入仅 **{fmt_num(avg_calories)}kcal**，对训练人群偏低："
            "训练日前后各补一次碳水+蛋白质加餐，避免训练表现被热量不足拖住"
        )
    if len(advice) < 3:
        advice.append(
            "保持训练日志与 RPE 记录：只有连续 2-4 周的数据才能判断「是真进步还是波动」，"
            "避免用单日数据频繁改计划"
        )

    lines += ["", "### 🎯 下周训练调整建议"]
    for index, tip in enumerate(advice[:3], start=1):
        lines.append(f"{index}. {tip}")

    # 风险提示：规范要求识别「连续高 RPE + 体重下降 + 训练频率过高」的过度训练信号
    lines += ["", "### ⚠️ 风险提示"]
    risks: List[str] = []
    if avg_rpe >= 8 and training_days >= 5:
        risks.append(
            f"出现过度训练信号：平均 RPE **{fmt_num(avg_rpe)}/10** 且每周训练 **{training_days}** 天。"
            "建议下周把容量降到平时的 40%-60%（重量不变、减少组数）安排减载，"
            "或安排 3-5 天完全休息"
        )
    if weight_change < -0.5 and avg_rpe >= 7.5:
        risks.append(
            f"高强度（RPE {fmt_num(avg_rpe)}）叠加体重下降（{weight_change:+.1f}kg），"
            "常见于「热量不足 + 训练量过大」，请优先保证睡眠 7-9 小时与蛋白质摄入"
        )
    if 0 < avg_calories < 1500:
        risks.append(
            f"日均热量仅 **{fmt_num(avg_calories)}kcal**，长期低于基础代谢会影响恢复与激素水平，"
            "建议尽快上调到合理区间，必要时咨询营养师"
        )
    if not risks:
        if training_days or total_volume > 0:
            risks.append(
                "本周未发现明显过度训练信号：RPE 与训练频率均在可控范围，"
                "继续留意关节疼痛、睡眠变差、训练欲望下降等早期预警即可"
            )
        else:
            risks.append(
                "本周无训练数据，无法评估过度训练风险；恢复训练后请先记录 2-3 周的 RPE 与体重趋势"
            )
    for risk in risks[:2]:
        lines.append(f"- {risk}")

    lines += [
        "",
        "> ℹ️ 本计划由周数据自动分析生成（未调用大模型润色，"
        "配置 `DEEPSEEK_API_KEY` 且 `MOCK_MODE=false` 后即为 AI 生成）。",
    ]

    plan = "\n".join(lines).strip()
    logger.info(
        "周计划生成完成(本地拼装): userId=%s week=%s 训练天数=%s 总容量=%s 体重变化=%s 字数=%d",
        user_id, week_label, training_days, fmt_num(total_volume),
        f"{weight_change:+.1f}", len(plan),
    )
    return plan
