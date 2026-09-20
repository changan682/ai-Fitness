"""验收脚本共用的「姿态评估测试图片」。

为什么需要这个模块
==================
姿态评估这条链路对图片有**两条**硬约束，踩任何一条都会让验收脚本在真实 AI 模式下失败，
而失败原因看起来都像「产品坏了」：

1. **尺寸**：宽和高都必须 > 10px。1x1 的「最小合法 PNG」会被模型直接拒绝
   （``<400> InternalError.Algo.InvalidParameter: The image length and width do not
   meet the model restrictions. [height:1 or width:1 must be larger than 10]``）。
   更糟的是经 Java 转发时，1x1 图连 ``ImageCompressor`` 都解不出来，
   用户看到的是「图片读取失败，请重新上传」，请求根本没到 Python。
2. **内容**：图片要能看清一个完整的深蹲动作。纯色块或过于粗糙的简笔图，
   模型会如实回「照片无法判断动作姿态」→ 按入参问题报 400 / 9003。
   这**不是**故障，而是模型在正确地拒绝一张没信息量的图。

历史教训：``smoke_test.py`` 与 ``verify_java_ai_endpoints.py`` 曾各自内联那张 1x1 图，
于是它们在 ``MOCK_MODE=true`` 时「恰好通过」（本地模拟打分不校验图片），
一旦接真实模型就必然失败 —— 而 README 却写着这两个脚本「全绿」。
本模块把测试图片收敛为**单一来源**，并在生成前就满足上面两条约束。

用法
====
``squat_image_bytes()`` 优先用 Pillow 生成一张 720x720 的深蹲剪影（有效像素充足、
模型的评估质量已在 ``verify_pose_multimodal.py`` 实测：能给出与动作对应的具体问题与建议）；
Pillow 不可用时退回内置的 120x160 PNG —— 它满足尺寸约束、不会触发上面第 1 条失败，
但因为内容粗糙，模型很可能按第 2 条回「无法判断」，此时姿态相关的断言仍会失败。
（``pillow>=10.0`` 已在 ``requirements.txt`` 中声明，正常环境不会走到该分支。）
"""

from __future__ import annotations

import base64
from functools import lru_cache

#: 内置回退图：120x160（两维均 > 10px），547 字节，不需要 Pillow
_FALLBACK_PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAHgAAACgCAIAAABIaz/HAAAB6klEQVR42u3dMW4CMRQE0Ky154xS5kiUiKNRII6RNqJI8ALJn79vuijN6jEY22uW5Xo5v8nrMxCABi2gQYMW0KAFNGjQAhq0zGbNutz3j8/vf56Oh5QrX1K2SW+I47hHuvKv/wX9HOUU69FAOcLarAP0fEkrl1qjQYMW0KA3ZXbJV3mJqNGgJ0tafMcjoNH3CNbfV8oYOn52jNi9W7JOk9qP/gfuIOXIWUfE7nOr6V2W+IjGDbKOX7CkWA+moFu9AKOHppuzEgXd4MRBq0a7Oft3fGWtjdGgtza0Zqk1GnT4UlCjQT8pNW8IaDRo0AIaNGhTDtAabVmo0aBBC2jQ5nYaDRq0gAYtoEHveG6n0aBBC2jQUhZ69vaKpxsIaNCgBTRo6QPtiehSHvp0PGQ9Mql5o4NejPhDjinWxmjQjcYNjQYNWkCDFtB7h272lSyNBt1rtaLRoEELaNACetfQ/VYrGg2612pFo0GDFtCgBfR+oVuuVjQadK/VikaDbpe1+MjQ5rNxLX59bdzXuCsO/Q32Nf0tmeLuwxA0aAEN+gWfe6ClO7Qnogto0KAFNGgBDRq0gAb9cNLv0mo06Mn4HRYBDRq0bEzMAZr0s2GpJ5Xi3Jfr5ex9bYwGLaBBgxbQoAU0aNACGrRM5QsIuH8QfZ3zqwAAAABJRU5ErkJggg=="
)

#: 回退图的原始字节（宽高 120x160 > 10），供 multipart 上传使用
FALLBACK_IMAGE_PNG: bytes = base64.b64decode(_FALLBACK_PNG_B64)

#: 回退图的 Base64 字符串，供 JSON 请求体使用
FALLBACK_IMAGE_B64: str = _FALLBACK_PNG_B64


@lru_cache(maxsize=1)
def make_squat_jpeg() -> bytes:
    """生成一张 720x720 的「下蹲剪影」合成图（JPEG）。

    刻意画上**膝盖内扣 + 弓背 + 杠铃位置偏高**，这样模型能指出具体问题，
    验收脚本里的「issues/suggestions 非空」才有真实内容可断言。

    依赖 Pillow（延迟导入）；未安装时返回 ``b""`` —— 调用方应改用
    :data:`FALLBACK_IMAGE_PNG`，并知道姿态断言可能因此失败。
    """
    try:
        from PIL import Image, ImageDraw  # noqa: PLC0415 - 可选依赖，延迟导入
    except ImportError:
        return b""

    import io
    import random

    w, h = 720, 720
    img = Image.new("RGB", (w, h), (225, 222, 216))
    d = ImageDraw.Draw(img)

    for y in range(h):      # 墙面渐变
        shade = int(232 - 26 * y / h)
        d.line([(0, y), (w, y)], fill=(shade, shade - 2, shade - 6))
    d.rectangle([0, 560, w, h], fill=(176, 141, 100))          # 木地板
    for x in range(0, w, 60):
        d.line([(x, 560), (x - 30, h)], fill=(150, 118, 82), width=2)

    skin, cloth = (214, 176, 150), (58, 66, 82)
    d.polygon([(258, 566), (306, 566), (312, 452), (268, 448)], fill=skin)   # 左小腿
    d.polygon([(404, 566), (452, 566), (446, 452), (402, 448)], fill=skin)   # 右小腿
    d.polygon([(268, 452), (312, 452), (392, 404), (352, 372)], fill=cloth)  # 左大腿
    d.polygon([(402, 448), (446, 452), (452, 408), (398, 400)], fill=cloth)  # 右大腿
    d.polygon([(340, 380), (400, 372), (424, 250), (366, 236)], fill=cloth)  # 躯干（前倾）
    d.ellipse([(330, 350), (430, 420)], fill=cloth)                          # 臀部
    d.polygon([(378, 250), (420, 246), (416, 214), (382, 216)], fill=skin)   # 颈
    d.ellipse([(368, 158), (436, 226)], fill=skin)                           # 头
    d.ellipse([(384, 176), (424, 210)], fill=(60, 48, 42))
    d.polygon([(392, 268), (428, 262), (470, 240), (456, 218), (410, 246)], fill=skin)
    d.polygon([(360, 272), (396, 268), (398, 236), (362, 240)], fill=skin)
    d.line([(232, 232), (612, 232)], fill=(120, 122, 126), width=14)         # 杠铃
    d.rectangle([(214, 196), (250, 268)], fill=(70, 72, 76))
    d.rectangle([(596, 196), (632, 268)], fill=(70, 72, 76))

    random.seed(7)
    for _ in range(9000):   # 噪点：削弱「矢量图」特征
        x, y = random.randrange(w), random.randrange(h)
        r, g, b = img.getpixel((x, y))
        n = random.randint(-12, 12)
        img.putpixel((x, y), (max(0, min(255, r + n)), max(0, min(255, g + n)),
                              max(0, min(255, b + n))))

    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=88)
    return buf.getvalue()


@lru_cache(maxsize=1)
def squat_image_bytes() -> bytes:
    """返回一张可被多模态模型评估的深蹲图片。

    优先 Pillow 生成的 720x720 剪影（JPEG）；Pillow 不可用时退回内置 120x160 PNG。
    调用方无需关心来源，直接把它当作 multipart 的图片内容或 Base64 的输入即可。
    """
    return make_squat_jpeg() or FALLBACK_IMAGE_PNG


def squat_image_b64() -> str:
    """同 :func:`squat_image_bytes`，但返回 Base64 字符串（JSON 请求体用）。"""
    return base64.b64encode(squat_image_bytes()).decode("ascii")
