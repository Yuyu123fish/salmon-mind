package com.yuyu.salmonmind.model.infrastructure.openai;

import com.yuyu.salmonmind.model.chat.ChatModelException;
import com.yuyu.salmonmind.model.chat.ChatModelHandle;
import com.yuyu.salmonmind.model.chat.ChatModelProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 生产 Chat 模型 Adapter：独占 base URL、API key、model name 的读取、校验与延迟创建。
 * 未配置时抛配置失败，由 agent 模块映射；不缓存失败结果，允许下次重试。
 */
@Component
class OpenAiCompatibleChatModelProvider implements ChatModelProvider {

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;

    OpenAiCompatibleChatModelProvider(
            @Value("${salmon.model.chat.base-url:}") String baseUrl,
            @Value("${salmon.model.chat.api-key:}") String apiKey,
            @Value("${salmon.model.chat.model-name:}") String modelName
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    @Override
    public ChatModelHandle get() {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(modelName)) {
            throw new ChatModelException("Chat 模型未配置");
        }
        return new ChatModelHandle(
                OpenAiChatModel.builder()
                        .openAiApi(OpenAiApi.builder()
                                .baseUrl(baseUrl)
                                .apiKey(apiKey)
                                .build())
                        .defaultOptions(OpenAiChatOptions.builder()
                                .model(modelName)
                                .build())
                        .build(),
                "openai-compatible",
                modelName);
    }
}
