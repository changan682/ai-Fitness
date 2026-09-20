"""配置默认值测试 —— 锁住三个「踩过坑」的数值，防止被改回去。

## 为什么这些默认值值得单测

1. **``EMBEDDING_DIM=768`` / ``LOCAL_EMBEDDING_MODEL=text2vec-base-chinese``**
   两者必须配套。`text2vec-large-chinese` 的 hidden size 是 **1024**，而 Milvus
   Collection 建的是 `dim=768`，混用不会在启动时报错，而是在**导入知识库时**
   才抛维度不匹配 —— 那时已经连上 Milvus、跑完了 Embedding，排查成本高。
2. **``MILVUS_NLIST=16`` / ``MILVUS_NPROBE=4``**
   nlist 的经验值是 √N（N=200 → √200≈14）。取 128 时每个聚类只分到 1-2 条向量，
   聚类近似失效、**召回率反而下降**；而且这种退化不会报错，只会让 RAG 悄悄变差。
   nprobe 必须与 nlist 配套：nprobe=16 只适用于 nlist=128。
3. **``POSE_MAX_DECODED_IMAGE_BYTES``**
   姿态评估的入参是 Base64 图片，解码后没有上限的话，几个并发就能把进程内存打爆。

配置默认值用 ``Settings(_env_file=None)`` 读取，避免被本机 `.env` 干扰
（`.env` 是开发者本地文件，可能覆盖成别的值）。
"""

from __future__ import annotations

from app.config import Settings


def _defaults(**overrides) -> Settings:
    """构造一份「只吃代码默认值」的配置（不读 .env）。"""
    return Settings(_env_file=None, **overrides)


class TestEmbeddingDefaults:

    def test_dim_should_be_768(self):
        """规范硬性要求 768 维，与 Milvus Collection 的 dim 严格对齐。"""
        assert _defaults().embedding_dim == 768

    def test_local_model_should_be_text2vec_base_chinese(self):
        """本地模型必须是 base 版（768 维）。

        large 版是 1024 维，会与 768 维 Collection 维度不匹配 —— 这正是本次修订的原因。
        """
        model = _defaults().local_embedding_model

        assert "text2vec-base-chinese" in model
        assert "large" not in model, "large 版是 1024 维，禁止作为本地 Embedding 模型"

    def test_local_model_name_is_fully_qualified(self):
        """必须是 HuggingFace 仓库全名，否则 SentenceTransformer 拉不到模型。"""
        assert _defaults().local_embedding_model == "shibing624/text2vec-base-chinese"


class TestMilvusIndexDefaults:

    def test_nlist_should_be_16(self):
        """nlist ≈ √N：N=200 → √200≈14，取 16。"""
        assert _defaults().milvus_nlist == 16

    def test_nprobe_should_be_4(self):
        """nprobe 必须与 nlist=16 配套（nprobe=16 只适用于 nlist=128）。"""
        assert _defaults().milvus_nprobe == 4

    def test_nprobe_should_not_exceed_nlist(self):
        """探测的聚类数超过总聚类数是无意义的（也说明参数没配套）。"""
        settings = _defaults()
        assert settings.milvus_nprobe <= settings.milvus_nlist

    def test_nlist_should_stay_near_sqrt_of_knowledge_size(self):
        """锁住「与数据规模匹配」这个口径：nlist 不得远超 √N（200 条知识 → 16）。"""
        nlist = _defaults().milvus_nlist
        assert nlist <= 32, (
            f"nlist={nlist} 远超 √N（N=200 时 √N≈14）：每个聚类只分到 1-2 条向量，"
            f"聚类近似失效、召回率反而下降。数据量涨到万级再按 √N 重调。"
        )

    def test_index_type_default(self):
        assert _defaults().milvus_index_type == "IVF_FLAT"
        assert _defaults().rag_top_k == 5, "规范要求 Top-5"


class TestPoseImageLimit:

    def test_max_decoded_image_bytes_default(self):
        """Base64 解码后的字节上限：默认 1.5MB（Java 侧已压到 ≤1MB，这里留余量兜底）。"""
        limit = _defaults().pose_max_decoded_image_bytes

        assert limit == 1_500_000
        assert limit > 1_000_000, "必须比 Java 侧的 1MB 压缩目标宽，否则正常图会被误拒"

    def test_limit_is_overridable_by_env_style_kwarg(self):
        """可通过环境变量覆盖，便于联调时临时放宽。"""
        assert _defaults(pose_max_decoded_image_bytes=5_000_000).pose_max_decoded_image_bytes \
            == 5_000_000


class TestHmacRelatedDefaults:

    def test_hmac_secret_has_a_default_but_is_placeholder(self):
        """默认密钥是占位符：生产必须覆盖，否则 Java 侧与 Python 侧会撞在同一个公开值上。"""
        assert _defaults().hmac_secret
        assert "change-me" in _defaults().hmac_secret

    def test_java_callback_url_default(self):
        assert _defaults().java_callback_url == "http://localhost:8080"
