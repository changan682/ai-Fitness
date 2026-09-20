#!/usr/bin/env python
"""姿态评估（多模态）端到端验证 —— 第 6 周「接真实多模态」的验收脚本。

## 它验证什么

1. **Key 与模型可用**：真的调一次通义千问 VL（不是打桩、不是 Mock）；
2. **Prompt 与 schema 对齐**：模型返回的 JSON 能被解析成规范 7.3 的响应结构；
3. **契约不变量**：``score_level`` 必须由 ``score`` 派生、字段齐全；
4. **模型是否真的在看图**：对一张「深蹲剪影」给出与动作相关的具体问题与建议
   （如果它只是在敷衍，issues 里会出现「无法判断」）。

## 用法

```powershell
# 用自带的合成剪影图（无需准备素材，验证链路是否通）
E:\\Anaconde\\python.exe scripts/verify_pose_multimodal.py

# 用自己的真实照片（**答辩演示建议用这个**）
E:\\Anaconde\\python.exe scripts/verify_pose_multimodal.py --image D:/photos/squat.jpg --action 深蹲
```

> 提示：真实照片的效果远好于合成图。手机拍一张侧面的深蹲照片即可，
> 注意**全身入镜、光线充足、动作完整**（模型的硬约束是图片宽高都 > 10px）。
"""

from __future__ import annotations

import argparse
import base64
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
# 同目录下的共用测试图片模块（scripts 自身也需在 sys.path 上）
sys.path.insert(0, str(Path(__file__).resolve().parent))

from _test_image import make_squat_jpeg  # noqa: E402  - 与另两个验收脚本共用同一张图
from app.agent import evaluate_pose  # noqa: E402
from app.config import settings  # noqa: E402
from app.multimodal import MultimodalError  # noqa: E402
from app.utils import AgentInputError  # noqa: E402

PASS = 0
FAIL = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASS, FAIL
    if ok:
        PASS += 1
    else:
        FAIL += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))


def make_synthetic_squat_png() -> bytes:
    """生成一张「下蹲剪影」合成图（故意画上膝盖内扣 + 弓背，看模型能否指出）。

    绘图实现已抽到 ``scripts/_test_image.py``：``smoke_test.py`` 与
    ``verify_java_ai_endpoints.py`` 也需要同一张图，三处共用一份避免再次出现
    「某个脚本各自内联了一张 1x1 图」那类不一致（那正是它们接真实模型后必然失败的原因）。

    依赖 Pillow；没装就返回空字节，由调用方提示用户改用 ``--image`` ——
    本脚本不退回内置的低清图：它的断言依赖「模型真的给出了评估」，
    用一张看不清的图只会得到「无法判断」，反而把原因搞混。
    """
    return make_squat_jpeg()


def main() -> int:
    parser = argparse.ArgumentParser(description="姿态评估（多模态）端到端验证")
    parser.add_argument("--image", help="待评估的图片路径（建议用真实照片）")
    parser.add_argument("--action", default="深蹲", help="动作名称，默认「深蹲」")
    parser.add_argument("--save", help="把本次使用的图片另存到该路径（便于复现/写论文配图）")
    args = parser.parse_args()

    print("=" * 72)
    print("姿态评估（多模态）端到端验证")
    print("=" * 72)
    print(f"  模型: {settings.qwen_vl_model}   MOCK_MODE={settings.mock_mode}   "
          f"多模态Key已配置={settings.multimodal_configured}")

    if args.image:
        image_bytes = Path(args.image).read_bytes()
        source = f"用户图片 {args.image}"
    else:
        image_bytes = make_synthetic_squat_png()
        source = "内置合成剪影图"
        if not image_bytes:
            print("\n未安装 Pillow，无法生成合成图；请用 --image 指定一张照片。")
            return 2

    if args.save:
        Path(args.save).write_bytes(image_bytes)

    print(f"  图片: {source}  {len(image_bytes)} 字节")
    print("-" * 72)

    # --- 前置检查 ---
    check("未处于 MOCK_MODE（否则不会真的调用模型）", not settings.mock_mode,
          "MOCK_MODE=true" if settings.mock_mode else "")
    check("多模态 Key 已配置", settings.multimodal_configured)

    # --- 真实调用 ---
    try:
        result = evaluate_pose(base64.b64encode(image_bytes).decode("ascii"), args.action)
    except AgentInputError as exc:
        # 「照片无法判断」属于入参问题：说明链路是通的，但素材不合格
        check("模型给出了可用的评估（未判为「无法判断」）", False, exc.message)
        print("\n  说明：多模态调用本身是成功的，是这张图不足以判断动作姿态。")
        print("        答辩演示请换一张全身入镜、光线充足的清晰照片（--image）。")
        return _summary()
    except MultimodalError as exc:
        check("多模态调用成功", False, str(exc)[:200])
        print("\n  说明：这是模型侧失败（Key/权限/网络/限额），检查 check_api_keys.py 的输出。")
        return _summary()

    check("多模态调用成功", True)
    check("score 在 0-100 内", 0 <= result.score <= 100, f"score={result.score}")
    check("score_level 由 score 严格派生", result.score_level == _level_of(result.score),
          f"{result.score} → {result.score_level}")
    check("issues 非空（有具体问题描述）", bool(result.issues))
    check("suggestions 非空（有可执行建议）", bool(result.suggestions))
    check("good_points 至少 1 条", bool(result.good_points))
    check("issues 与 suggestions 数量一致", len(result.issues) == len(result.suggestions),
          f"{len(result.issues)} vs {len(result.suggestions)}")
    check("评估时间已填充", bool(result.evaluated_at))

    print("-" * 72)
    print(f"score={result.score}  level={result.score_level}")
    print("问题：")
    for item in result.issues:
        print(f"  - {item}")
    print("纠正建议：")
    for item in result.suggestions:
        print(f"  - {item}")
    print("做得好：")
    for item in result.good_points:
        print(f"  - {item}")

    return _summary()


def _level_of(score: int) -> str:
    from app.agent import score_level  # noqa: PLC0415 - 仅用于断言，延迟导入避免循环
    return score_level(score)


def _summary() -> int:
    print("\n" + "=" * 72)
    print(f"结果：通过 {PASS} 项，失败 {FAIL} 项")
    print("=" * 72)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
