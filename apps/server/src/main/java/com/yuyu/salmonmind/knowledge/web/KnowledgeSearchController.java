package com.yuyu.salmonmind.knowledge.web;

import com.yuyu.salmonmind.knowledge.api.KnowledgeSearchResult;
import com.yuyu.salmonmind.knowledge.api.KnowledgeSearchService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Knowledge 诊断检索的 HTTP 转换层；不暴露索引、向量和模型原始响应。 */
@RestController
@RequestMapping("/api/knowledge")
class KnowledgeSearchController {

    private final KnowledgeSearchService searchService;

    KnowledgeSearchController(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    /** 使用 POST 承载长查询，正常降级/空结果仍以 200 结构化返回。 */
    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    KnowledgeSearchResult search(@RequestBody SearchRequest request) {
        return searchService.search(request == null ? null : request.query());
    }

    record SearchRequest(String query) {
    }
}
