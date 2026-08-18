package com.yuyu.salmonmind.model.embedding;

import java.util.List;

/**
 * 文本 Embedding 的稳定模型边界。业务模块只知道批量文本和固定维数，
 * 不直接依赖 SiliconFlow/OpenAI-compatible HTTP 协议。
 */
public interface EmbeddingService {

    int DIMENSIONS = 2560;

    /**
     * 按输入顺序返回向量；调用方必须按原顺序把结果与文本绑定。
     *
     * @param texts 非空文本批次，不能包含空白文本
     * @return 与输入一一对应的固定 {@link #DIMENSIONS} 维向量
     * @throws EmbeddingException 未配置、提供方失败或响应维数不符合合同
     */
    EmbeddingResult embed(List<String> texts);
}
