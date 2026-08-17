package com.yuyu.salmonmind.knowledge.application.port;

import com.yuyu.salmonmind.knowledge.domain.ParsedDocument;

import java.nio.file.Path;

/** Tika 解析边界；Tika 类型和 parser 配置不暴露给 Knowledge API。 */
public interface DocumentParserPort {

    /**
     * 探测原件内容类型；失败时抛出稳定的 INVALID_UPLOAD，调用方不得把探测失败的原件入库。
     *
     * @param file 已落盘的临时原件
     * @return 不含参数的媒体类型
     */
    String detect(Path file);

    /**
     * 解析为平台拥有的规范化文本；不得返回嵌入文档或 OCR 伪造内容。
     *
     * @param file 原件路径
     * @param expectedMediaType 上传阶段确认的内容类型
     * @return 带页数和字符数的解析结果
     */
    ParsedDocument parse(Path file, String expectedMediaType);
}
