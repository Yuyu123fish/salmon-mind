package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyu.salmonmind.agent.api.AgentExecutionException;
import com.yuyu.salmonmind.agent.api.AgentCallChainReference;
import com.yuyu.salmonmind.agent.api.AgentMessage;
import com.yuyu.salmonmind.agent.api.AgentRequest;
import com.yuyu.salmonmind.agent.api.AgentResult;
import com.yuyu.salmonmind.agent.api.AgentRunTraceItem;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentToolCompleted;
import com.yuyu.salmonmind.agent.api.AgentToolFailed;
import com.yuyu.salmonmind.agent.api.AgentToolOutcomeDetail;
import com.yuyu.salmonmind.agent.api.AgentToolStarted;
import com.yuyu.salmonmind.agent.api.CheckpointPolicy;
import com.yuyu.salmonmind.codebase.api.AgentCallChainService;
import com.yuyu.salmonmind.codebase.api.CallChainConfirmation;
import com.yuyu.salmonmind.codebase.api.CallChainReference;
import com.yuyu.salmonmind.codebase.api.CodebaseService;
import com.yuyu.salmonmind.codebase.api.RepositoryEvidenceService;
import com.yuyu.salmonmind.codebase.api.RepositoryResolution;
import com.yuyu.salmonmind.model.chat.ChatModelHandle;
import com.yuyu.salmonmind.model.chat.ChatModelProvider;
import com.yuyu.salmonmind.persistence.redis.RedissonClientProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Feature 003 S1-01 Tool Runtime 硬 Gate：在锁定版本（Spring AI 1.1.2 /
 * Spring AI Alibaba 1.1.2.2 / Redisson 3.22.0）上，用真实 ReactAgent + RedisSaver +
 * 确定性 ChatModel 与测试专用只读 ToolCallback 证明：
 *
 * <ol>
 *   <li>ToolCallback 被真实执行，tool result 回到下一次模型调用；</li>
 *   <li>工具生命周期由平台 ToolLifecycleInterceptor 映射为 started/completed/failed，
 *       每个 Tool Call 至多一个终态，随后恰好一次 Agent 终态；</li>
 *   <li>工具异常只产生一次 failed 观察并收束为单终态；</li>
 *   <li>超长工具结果在进入模型上下文前被有界截断；</li>
 *   <li>REBUILD_FROM_PROJECTION 释放旧 Checkpoint 后只看到显式 JSONL 投影，
 *       不携带上一轮工具消息；REUSE_IF_MATCH 默认路径不受影响。</li>
 * </ol>
 *
 * <p>不调用真实模型、不绕过 ReactAgent/ToolNode；本测试的工具只经包内构造 seam
 * 注入，不代表生产 Spring Bean 的本地 Knowledge Tool。
 */
@Testcontainers
class AgentToolRuntimeIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static String redisUrl;

    private final List<ReactAgentSessionAdapter> adapters = new ArrayList<>();

    @BeforeAll
    static void redisUrl() {
        redisUrl = "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    @AfterEach
    void closeAdapters() {
        adapters.forEach(ReactAgentSessionAdapter::close);
        adapters.clear();
    }

    @Test
    void executesRealToolLoopAndEmitsLifecycleEvents() {
        var tool = new RecordingSearchTool();
        var model = new ToolCallingChatModel();
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "loop-thread", null, UUID.randomUUID(),
                userList("请帮我搜索 salmon"), CheckpointPolicy.REUSE_IF_MATCH));

        // 最终回答非空，最终 usage 可取得
        assertThat(result.text()).isEqualTo(ToolCallingChatModel.FINAL_ANSWER);
        assertThat(result.usage().totalTokens()).isEqualTo(49);

        // 1. ToolCallback 被真实执行一次，参数来自模型 tool call
        assertThat(tool.calls).hasSize(1);
        assertThat(tool.calls.get(0)).contains("salmon");

        // 2. 第二次模型调用包含同一 Tool Call ID 对应的 tool result
        ToolResponseMessage toolResult = toolResponseOf(model.calls.get(1));
        assertThat(toolResult.getResponses().get(0).id()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
        assertThat(toolResult.getResponses().get(0).name()).isEqualTo(ToolCallingChatModel.TOOL_NAME);
        assertThat(toolResult.getResponses().get(0).responseData()).contains("SalmonMind");

        // 3. started → completed 顺序唯一，随后一次 complete；无 failed
        assertThat(events.started).hasSize(1);
        assertThat(events.started.get(0).toolCallId()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
        assertThat(events.started.get(0).toolName()).isEqualTo(ToolCallingChatModel.TOOL_NAME);
        assertThat(events.started.get(0).safeQuerySummary()).isEqualTo("工具执行中");
        assertThat(events.started.get(0).safeQuerySummary()).doesNotContain("salmon");
        assertThat(events.completed).hasSize(1);
        assertThat(events.completed.get(0).toolCallId()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
        assertThat(events.completed.get(0).toolName()).isEqualTo(ToolCallingChatModel.TOOL_NAME);
        assertThat(events.completed.get(0).durationMillis()).isGreaterThanOrEqualTo(0);
        assertThat(events.failed).isEmpty();
        // 恰好一次 Agent 终态：complete，无 error
        assertThat(events.result()).isNotNull();
        assertThat(events.error()).isNull();
        // delta 拼接即最终回答
        assertThat(String.join("", events.deltas)).isEqualTo(ToolCallingChatModel.FINAL_ANSWER);
        assertThat(events.reasoning).containsExactly("需要先查询资料。", "资料足够，可以作答。");
        assertThat(result.trace()).satisfiesExactly(
                item -> assertThat(item).extracting(AgentRunTraceItem::kind, AgentRunTraceItem::text)
                        .containsExactly(AgentRunTraceItem.Kind.REASONING, "需要先查询资料。"),
                item -> assertThat(item).extracting(
                        AgentRunTraceItem::kind, AgentRunTraceItem::toolCallId, AgentRunTraceItem::toolStatus)
                        .containsExactly(AgentRunTraceItem.Kind.TOOL, ToolCallingChatModel.TOOL_CALL_ID,
                        AgentRunTraceItem.ToolStatus.COMPLETED),
                item -> assertThat(item).extracting(AgentRunTraceItem::kind, AgentRunTraceItem::text)
                        .containsExactly(AgentRunTraceItem.Kind.REASONING, "资料足够，可以作答。"));
    }

    @Test
    void repairsMissingEvidenceThenStagesAndPreparesOneCallChain() {
        UUID repositoryId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID answerEntryId = UUID.randomUUID();
        UUID callChainId = UUID.randomUUID();
        CodebaseService codebase = mock(CodebaseService.class);
        when(codebase.resolveRepository((String) null)).thenReturn(RepositoryResolution.resolved(
                new RepositoryResolution.ResolvedRepository(
                        repositoryId, "salmon-mind", "D:/salmon-mind", true,
                        "READY", "main", "abc123", false)));

        RepositoryEvidenceService evidence = mock(RepositoryEvidenceService.class);
        RepositoryEvidenceService.EvidenceMetadata metadata = new RepositoryEvidenceService.EvidenceMetadata(
                repositoryId, "salmon-mind", "test", "main", "abc123", false,
                true, false, 1, 1, false, null, null);
        when(evidence.listDirectory(any())).thenReturn(new RepositoryEvidenceService.ListDirectoryResult(
                metadata, List.of(new RepositoryEvidenceService.DirectoryEntry("src", "src", true, false))));
        when(evidence.readFile(any())).thenAnswer(invocation -> {
            RepositoryEvidenceService.ReadFileQuery query = invocation.getArgument(0);
            if ("src/Entry.java".equals(query.relativePath())) {
                return new RepositoryEvidenceService.ReadFileResult(
                        metadata, query.relativePath(), false, query.startLine(), query.startLine() + 1,
                        "void enter() {\n  run();");
            }
            if ("src/Service.java".equals(query.relativePath()) && query.startLine() == 1) {
                return new RepositoryEvidenceService.ReadFileResult(
                        metadata, query.relativePath(), false, 1, 1, "void run() {");
            }
            if ("src/Service.java".equals(query.relativePath()) && query.startLine() == 2) {
                return new RepositoryEvidenceService.ReadFileResult(
                        metadata, query.relativePath(), false, 2, 2, "  return;");
            }
            throw new AssertionError("unexpected read path: " + query.relativePath());
        });
        AgentCallChainService callChains = mock(AgentCallChainService.class);
        when(callChains.prepare(any())).thenReturn(new CallChainReference(
                callChainId, repositoryId, "入口到服务", 2, 1));

        CodebaseFlowChatModel model = new CodebaseFlowChatModel();
        ReactAgentSessionAdapter adapter = newCodebaseAdapter(model, codebase, evidence);
        ReflectionTestUtils.setField(adapter, "callChainService", callChains);
        RecordingListener events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                conversationId.toString(), null, answerEntryId,
                userList("请分析当前仓库的入口到服务流程"), CheckpointPolicy.REBUILD_FROM_PROJECTION));

        assertThat(result.text()).isEqualTo(CodebaseFlowChatModel.FINAL_ANSWER);
        assertThat(model.toolNames).containsExactly(
                "list_repository_directory", "read_repository_file", "read_repository_file",
                "stage_call_chain", "read_repository_file", "stage_call_chain");
        assertThat(events.started).extracting(AgentToolStarted::toolName).containsExactly(
                "list_repository_directory", "read_repository_file", "read_repository_file",
                "stage_call_chain", "read_repository_file", "stage_call_chain");
        assertThat(events.failed).singleElement()
                .extracting(AgentToolFailed::toolName, AgentToolFailed::stableErrorCode)
                .containsExactly("stage_call_chain", "CALL_CHAIN_EVIDENCE_INSUFFICIENT");
        assertThat(events.completed).hasSize(5);
        assertThat(result.callChain()).isEqualTo(new AgentCallChainReference(
                callChainId, repositoryId, "入口到服务", 2, 1));
        verify(codebase, times(1)).resolveRepository((String) null);
        verify(evidence, times(3)).readFile(any());
        verify(callChains).prepare(any());

        adapter.confirmCallChains(List.of(result.callChain()), answerEntryId);
        verify(callChains).confirm(new CallChainConfirmation(repositoryId, callChainId, answerEntryId));
    }

    @Test
    void projectsArgumentsBeforeStartedAndKeepsCanaryOutsideDisplayEvents() {
        String arguments = "{\"query\":\" salmon \\t\",\"freshness\":\"week\",\"count\":3,"
                + "\"secret\":\"canary-secret\"}";
        var tool = new RecordingSearchTool("search_web_bocha", false, "网页结果");
        var model = new ToolCallingChatModel("已完成网页核验。", "search_web_bocha", arguments);
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "request-detail-gate-thread", null, UUID.randomUUID(),
                userList("核对网页"), CheckpointPolicy.REUSE_IF_MATCH));

        assertThat(tool.calls).containsExactly(arguments);
        assertThat(events.started).singleElement().satisfies(event -> {
            assertThat(event.toolCallId()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
            assertThat(event.safeQuerySummary()).isEqualTo("salmon");
            assertThat(event.requestDetail()).isNotNull().satisfies(detail -> {
                assertThat(detail.querySummary()).isEqualTo("salmon");
                assertThat(detail.freshness()).isEqualTo("week");
                assertThat(detail.count()).isEqualTo(3);
                assertThat(detail.freshnessDefaulted()).isFalse();
                assertThat(detail.countDefaulted()).isFalse();
            });
            assertThat(event.toString()).doesNotContain("canary-secret", arguments);
        });
        assertThat(result.trace()).filteredOn(item -> item.kind() == AgentRunTraceItem.Kind.TOOL)
                .singleElement().satisfies(item -> {
            assertThat(item.safeSummary()).isEqualTo("salmon");
            assertThat(item.requestDetail()).isNotNull();
            assertThat(item.toString()).doesNotContain("canary-secret", arguments);
        });
    }

    @Test
    void toolExceptionProducesSingleFailedAndSingleTerminal() {
        var tool = new RecordingSearchTool(true);
        var model = new ToolCallingChatModel();
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "fail-thread", null, UUID.randomUUID(),
                userList("搜索一个会失败的话题"), CheckpointPolicy.REUSE_IF_MATCH));

        // 4. 工具抛异常：一次 failed，无 completed，且只有一次 Agent 终态（complete）
        assertThat(events.failed).hasSize(1);
        assertThat(events.failed.get(0).toolCallId()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
        assertThat(events.failed.get(0).toolName()).isEqualTo(ToolCallingChatModel.TOOL_NAME);
        assertThat(events.failed.get(0).stableErrorCode()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(events.failed.get(0).safeMessage()).isEqualTo("工具执行失败");
        assertThat(events.failed.get(0).safeMessage()).doesNotContain("gate 测试注入异常");
        assertThat(events.completed).isEmpty();
        assertThat(events.result()).isNotNull();
        assertThat(events.error()).isNull();
        assertThat(result.text()).isEqualTo(ToolCallingChatModel.FINAL_ANSWER);
        // 错误结果仍以 ToolResponseMessage 形式回到模型，框架不中断循环
        assertThat(toolResponseOf(model.calls.get(1)).getResponses().get(0).id())
                .isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
    }

    @Test
    void boundsOverlongToolResultBeforeNextModelCall() {
        var tool = new RecordingSearchTool(false, "x".repeat(5000));
        var model = new ToolCallingChatModel();
        // 结果上限 100 字符：证明存在进入模型上下文前的有界控制点
        var adapter = newAdapter(model, List.of(tool), 100);
        var events = new RecordingListener();

        completeSync(adapter, events, new AgentRequest(
                "bound-thread", null, UUID.randomUUID(),
                userList("返回超长结果"), CheckpointPolicy.REUSE_IF_MATCH));

        // 5. 超长结果在进入模型前被截断到上限；生命周期仍以 completed 收尾
        String responseData = toolResponseOf(model.calls.get(1)).getResponses().get(0).responseData();
        assertThat(responseData).hasSize(100);
        assertThat(events.completed).hasSize(1);
        assertThat(events.failed).isEmpty();
    }

    @Test
    void actualToolResultsDoNotConsumeACumulativePerRunGate() {
        var tool = new RecordingSearchTool(false, "x".repeat(1_000));
        var model = new BudgetCallingChatModel();
        // 旧实现会把第一条结果计入累计 Gate；新构造合同已没有这项预算，两次结果都可进入模型。
        var adapter = newAdapter(model, List.of(tool), 200_000, 32);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "context-budget-thread", null, UUID.randomUUID(),
                userList("连续搜索"), CheckpointPolicy.REUSE_IF_MATCH));

        assertThat(tool.calls).hasSize(2);
        assertThat(events.failed).isEmpty();
        assertThat(result.text()).isEqualTo(BudgetCallingChatModel.FINAL_ANSWER);
    }

    @Test
    void modelCallLimitHookHardStopsARepeatedToolLoop() {
        var tool = new RecordingSearchTool();
        var model = new LoopingToolChatModel();
        var adapter = newAdapter(model, List.of(tool), 200_000, 2);
        var events = new RecordingListener();

        adapter.stream(new AgentRequest(
                "max-steps-thread", null, UUID.randomUUID(), userList("持续调用工具"),
                CheckpointPolicy.REUSE_IF_MATCH), events);

        assertThat(events.result()).isNull();
        assertThat(events.error()).isNotNull();
        assertThat(events.error().code()).isEqualTo(AgentExecutionException.AgentErrorCode.AGENT_LOOP_LIMIT_REACHED);
        assertThat(model.calls).hasSize(2);
        assertThat(tool.calls).hasSize(2);
    }

    @Test
    void decoratesSourceResultAndReturnsOnlyReferencedCitation() {
        String source = """
                {"status":"SUCCESS","reason":"NONE","sourceKind":"WEB","provider":"BOCHA","items":[{"title":"官方页面","url":"https://example.com","site":"example.com","snippet":"摘要","retrievedAt":"2026-08-17T00:00:00Z"}]}
                """;
        var tool = new RecordingSearchTool(false, source);
        var model = new ToolCallingChatModel("回答依据 [W1]，未知标记 [W99] 不应成为来源。");
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "citation-thread", null, UUID.randomUUID(), userList("搜索网页"),
                CheckpointPolicy.REUSE_IF_MATCH));

        assertThat(toolResponseOf(model.calls.get(1)).getResponses().get(0).responseData())
                .contains("referenceId\":\"W1");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).referenceId()).isEqualTo("W1");
        assertThat(result.citations().get(0).citationNote()).contains("回答依据");
        assertThat(result.retrievedSources()).hasSize(1)
                .singleElement()
                .satisfies(retrieved -> {
                    assertThat(retrieved.sourceExcerpt()).isEqualTo("摘要");
                    assertThat(retrieved.originToolCallId()).isEqualTo(ToolCallingChatModel.TOOL_CALL_ID);
                    assertThat(retrieved.resultPosition()).isEqualTo(1);
                    assertThat(retrieved.providerRank()).isNull();
                });
        assertThat(events.completed).singleElement().satisfies(event -> {
            assertThat(event.provider()).isEqualTo("BOCHA");
            assertThat(event.sourceCount()).isEqualTo(1);
            assertThat(event.outcomeDetail().resultStatus())
                    .isEqualTo(AgentToolOutcomeDetail.ResultStatus.SUCCESS);
            assertThat(event.outcomeDetail().stableReasonCode()).isEqualTo("NONE");
            assertThat(event.outcomeDetail().resultTruncated()).isFalse();
        });
    }

    @Test
    void blocksWebProviderBeforeHandlerWhenUserForbidsBrowsing() {
        var tool = new RecordingSearchTool("search_web_bocha", false,
                "{\"status\":\"SUCCESS\",\"sourceKind\":\"WEB\",\"provider\":\"BOCHA\",\"items\":[]}");
        var model = new ToolCallingChatModel("已按要求不联网回答。", "search_web_bocha");
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "web-disabled-thread", null, UUID.randomUUID(),
                userList("请只根据当前对话回答，不要联网"), CheckpointPolicy.REUSE_IF_MATCH));

        assertThat(tool.calls).isEmpty();
        assertThat(events.failed).singleElement()
                .extracting(AgentToolFailed::stableErrorCode)
                .isEqualTo("WEB_SEARCH_DISABLED");
        assertThat(result.text()).isEqualTo("已按要求不联网回答。");
    }

    @Test
    void keepsLocalSearchAvailableWhenOnlyBrowsingIsForbidden() {
        var tool = new RecordingSearchTool("search_local_knowledge", false,
                "{\"status\":\"SUCCESS\",\"sourceKind\":\"LOCAL\",\"provider\":\"LOCAL\",\"items\":[]}");
        var model = new ToolCallingChatModel("已依据本地资料回答。", "search_local_knowledge");
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "local-allowed-thread", null, UUID.randomUUID(),
                userList("请查本地资料，但不要联网"), CheckpointPolicy.REUSE_IF_MATCH));

        assertThat(tool.calls).hasSize(1);
        assertThat(events.completed).singleElement().extracting(AgentToolCompleted::sourceCount).isEqualTo(0);
        assertThat(result.text()).isEqualTo("已依据本地资料回答。");
    }

    @Test
    void blocksLocalProviderBeforeHandlerWhenUserForbidsAllRetrieval() {
        var tool = new RecordingSearchTool("search_local_knowledge", false,
                "{\"status\":\"SUCCESS\",\"sourceKind\":\"LOCAL\",\"provider\":\"LOCAL\",\"items\":[]}");
        var model = new ToolCallingChatModel("已按要求只使用当前对话。", "search_local_knowledge");
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        AgentResult result = completeSync(adapter, events, new AgentRequest(
                "local-disabled-thread", null, UUID.randomUUID(),
                userList("只根据当前对话回答，不要查询任何资料"), CheckpointPolicy.REUSE_IF_MATCH));

        assertThat(tool.calls).isEmpty();
        assertThat(events.failed).singleElement()
                .extracting(AgentToolFailed::stableErrorCode)
                .isEqualTo("LOCAL_SEARCH_DISABLED");
        assertThat(result.text()).isEqualTo("已按要求只使用当前对话。");
    }

    @Test
    void keepsInvalidProviderResponseDistinctInToolTrace() {
        var tool = new RecordingSearchTool(false,
                "{\"status\":\"UNAVAILABLE\",\"reason\":\"INVALID_RESPONSE\","
                        + "\"sourceKind\":\"WEB\",\"provider\":\"BOCHA\",\"items\":[]}");
        var model = new ToolCallingChatModel("未完成网页核验，仍返回当前对话结果。", RecordingSearchTool.NAME);
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        completeSync(adapter, events, new AgentRequest(
                "invalid-provider-thread", null, UUID.randomUUID(), userList("核对网页"),
                CheckpointPolicy.REUSE_IF_MATCH));

        assertThat(events.failed).singleElement()
                .extracting(AgentToolFailed::stableErrorCode)
                .isEqualTo("WEB_SEARCH_INVALID_RESPONSE");
    }

    @Test
    void keepsOrdinaryProviderFailureDistinctFromInvalidResponse() {
        var tool = new RecordingSearchTool(false,
                "{\"status\":\"UNAVAILABLE\",\"reason\":\"PROVIDER_FAILED\","
                        + "\"sourceKind\":\"WEB\",\"provider\":\"BOCHA\",\"items\":[]}");
        var model = new ToolCallingChatModel("未完成网页核验，仍返回当前对话结果。", RecordingSearchTool.NAME);
        var adapter = newAdapter(model, List.of(tool), 200_000);
        var events = new RecordingListener();

        completeSync(adapter, events, new AgentRequest(
                "provider-failure-thread", null, UUID.randomUUID(), userList("核对网页"),
                CheckpointPolicy.REUSE_IF_MATCH));

        assertThat(events.failed).singleElement()
                .extracting(AgentToolFailed::stableErrorCode)
                .isEqualTo("WEB_SEARCH_PROVIDER_FAILED");
    }

    @Test
    void rebuildFromProjectionDoesNotCarryPreviousToolMessages() {
        var tool = new RecordingSearchTool();
        var model = new ToolCallingChatModel();
        var adapter = newAdapter(model, List.of(tool), 200_000);

        // 第一轮：含一次工具执行，RedisSaver 在循环中保存 assistant(tool call) 与 tool result
        completeSync(adapter, new RecordingListener(), new AgentRequest(
                "rebuild-thread", null, UUID.randomUUID(),
                userList("第一轮：搜索 salmon")));
        String round1ThreadId = activeThreadId("rebuild-thread");
        assertThat(round1ThreadId).isNotBlank();

        UUID round2AnswerLeafId = UUID.randomUUID();
        // 第二轮：强制从 JSONL 投影重建，不得看到上一轮原始 tool call/result
        completeSync(adapter, new RecordingListener(), new AgentRequest(
                "rebuild-thread", null, round2AnswerLeafId,
                userList("第二轮：换个问题"), CheckpointPolicy.REBUILD_FROM_PROJECTION));

        List<Message> firstCallOfRound2 = model.calls.get(2);
        assertThat(visible(firstCallOfRound2)).containsExactly(user("第二轮：换个问题"));
        assertThat(firstCallOfRound2).noneMatch(ToolResponseMessage.class::isInstance);
        // 重建释放了旧 Checkpoint：RedisSaver 内部线程身份已更换
        assertThat(activeThreadId("rebuild-thread")).isNotEqualTo(round1ThreadId);

        // 6. 既有 REUSE_IF_MATCH 路径不受影响：标记一致时复用，不释放 Checkpoint，
        //    且证明 RedisSaver 确实保存了工具轮消息（第三轮模型能看到它们）
        String round2ThreadId = activeThreadId("rebuild-thread");
        completeSync(adapter, new RecordingListener(), new AgentRequest(
                "rebuild-thread", round2AnswerLeafId, UUID.randomUUID(),
                userList("第三轮：继续"), CheckpointPolicy.REUSE_IF_MATCH));
        assertThat(activeThreadId("rebuild-thread")).isEqualTo(round2ThreadId);
        List<Message> reusedCall = model.calls.get(4);
        assertThat(visible(reusedCall)).contains(user("第三轮：继续"));
        assertThat(reusedCall).anyMatch(ToolResponseMessage.class::isInstance);
    }

    @Test
    void parallelReadOnlyToolsUseReverseCompletionButOriginalModelResultOrder() throws Exception {
        var tool = new ParallelGateTool();
        var model = new ParallelCallingChatModel();
        var adapter = newAdapter(model, List.of(tool.left, tool.right), 200_000);
        var events = new RecordingListener();

        AgentResult[] result = new AgentResult[1];
        Thread run = new Thread(() -> result[0] = completeSync(adapter, events, new AgentRequest(
                "parallel-gate-thread", null, UUID.randomUUID(), userList("并行查询"),
                CheckpointPolicy.REUSE_IF_MATCH)));
        run.start();

        assertThat(tool.entered.await(5, TimeUnit.SECONDS)).isTrue();
        // 两个工具都已进入后，先放行 right，再放行 left，形成确定的反向完成顺序。
        tool.allowRight.countDown();
        // 必须等生命周期拦截器已经观察到 right 返回，才能放行 left；
        // 工具函数内部的记录先于函数真正返回，单独等待它会留下线程调度竞态。
        assertThat(events.parallelRightCompleted.await(5, TimeUnit.SECONDS)).isTrue();
        tool.allowLeft.countDown();
        run.join(5_000);

        assertThat(run.isAlive()).isFalse();
        assertThat(result[0].text()).isEqualTo("并行结果已按调用顺序收集。");
        assertThat(events.completed).extracting(AgentToolCompleted::toolName)
                .containsExactly("parallel_right", "parallel_left");
        // 生命周期回调的先后以实际观察到的完成顺序为准；模型收到的 ToolResponse 仍按原调用列表。
        assertThat(tool.completionOrder).containsExactly("parallel_right", "parallel_left");
        ToolResponseMessage responses = toolResponseOf(model.calls.get(1));
        assertThat(responses.getResponses()).extracting(ToolResponseMessage.ToolResponse::name)
                .containsExactly("parallel_left", "parallel_right");
    }

    @Test
    void matchesRequestDetailsToConcurrentToolCallIdsAndCallbackArguments() throws Exception {
        String leftArguments = "{\"query\":\"left query\",\"count\":2}";
        String rightArguments = "{\"query\":\"right query\",\"freshness\":\"day\"}";
        var tools = new ParallelGateTool("search_web_bocha", "search_web_searchapi");
        var model = new ParallelCallingChatModel(
                "search_web_bocha", "search_web_searchapi", leftArguments, rightArguments);
        var adapter = newAdapter(model, List.of(tools.left, tools.right), 200_000);
        var events = new RecordingListener();
        AgentResult[] result = new AgentResult[1];

        Thread run = new Thread(() -> result[0] = completeSync(adapter, events, new AgentRequest(
                "parallel-request-detail-thread", null, UUID.randomUUID(), userList("并行核对"),
                CheckpointPolicy.REUSE_IF_MATCH)));
        run.start();
        assertThat(tools.entered.await(5, TimeUnit.SECONDS)).isTrue();
        tools.allowRight.countDown();
        assertThat(tools.right.returned.await(5, TimeUnit.SECONDS)).isTrue();
        tools.allowLeft.countDown();
        run.join(5_000);

        assertThat(run.isAlive()).isFalse();
        assertThat(result[0]).isNotNull();
        assertThat(tools.left.arguments).containsExactly(leftArguments);
        assertThat(tools.right.arguments).containsExactly(rightArguments);
        assertThat(events.started).extracting(AgentToolStarted::toolCallId)
                .containsExactlyInAnyOrder("parallel-call-left", "parallel-call-right");
        assertThat(events.started).filteredOn(event -> event.toolCallId().equals("parallel-call-left"))
                .singleElement().satisfies(event -> assertThat(event.requestDetail()).isNotNull()
                        .extracting("querySummary", "count", "countDefaulted")
                        .containsExactly("left query", 2, false));
        assertThat(events.started).filteredOn(event -> event.toolCallId().equals("parallel-call-right"))
                .singleElement().satisfies(event -> assertThat(event.requestDetail()).isNotNull()
                        .extracting("querySummary", "freshness", "freshnessDefaulted")
                        .containsExactly("right query", "day", false));
    }

    @Test
    void frameworkToolTimeoutProducesOneStableFailureAndSuppressesLateCompletion() throws Exception {
        var tool = new BlockingTool();
        var model = new SingleBlockingToolModel();
        var adapter = newAdapter(model, List.of(tool), 200_000, 32, Duration.ofSeconds(1));
        var events = new RecordingListener();

        Thread run = new Thread(() -> completeSync(adapter, events, new AgentRequest(
                "timeout-gate-thread", null, UUID.randomUUID(), userList("执行慢工具"),
                CheckpointPolicy.REUSE_IF_MATCH)));
        run.start();
        assertThat(tool.entered.await(5, TimeUnit.SECONDS)).isTrue();
        run.join(8_000);
        assertThat(run.isAlive()).isFalse();
        assertThat(events.failed).singleElement()
                .extracting(AgentToolFailed::stableErrorCode)
                .isEqualTo(ToolLifecycleInterceptor.TOOL_EXECUTION_TIMEOUT);
        assertThat(events.completed).isEmpty();
        assertThat(events.result()).isNotNull();

        // 让底层同步回调迟到返回；终态 fence 不允许再追加 completed 或新的 Agent 终态。
        tool.release.countDown();
        assertThat(tool.returned.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events.failed).hasSize(1);
        assertThat(events.completed).isEmpty();
    }

    @Test
    void timeoutDoesNotCancelIndependentParallelTool() throws Exception {
        var tools = new ParallelTimeoutTools();
        var model = new ParallelTimeoutCallingChatModel();
        var adapter = newAdapter(model, List.of(tools.blocking, tools.fast), 200_000,
                32, Duration.ofSeconds(1));
        var events = new RecordingListener();
        AgentResult[] result = new AgentResult[1];

        Thread run = new Thread(() -> result[0] = completeSync(adapter, events, new AgentRequest(
                "parallel-timeout-gate-thread", null, UUID.randomUUID(), userList("并行执行慢工具"),
                CheckpointPolicy.REUSE_IF_MATCH)));
        run.start();
        assertThat(tools.blocking.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tools.fast.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tools.fast.returned.await(5, TimeUnit.SECONDS)).isTrue();
        run.join(8_000);

        assertThat(run.isAlive()).isFalse();
        assertThat(result[0]).isNotNull();
        assertThat(events.failed).singleElement()
                .extracting(AgentToolFailed::stableErrorCode)
                .isEqualTo(ToolLifecycleInterceptor.TOOL_EXECUTION_TIMEOUT);
        assertThat(events.completed).singleElement()
                .extracting(AgentToolCompleted::toolName)
                .isEqualTo("fast_tool");
        assertThat(toolResponseOf(model.calls.get(1)).getResponses())
                .extracting(ToolResponseMessage.ToolResponse::name)
                .containsExactly("blocking_tool", "fast_tool");

        tools.blocking.release.countDown();
        assertThat(tools.blocking.returned.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events.failed).hasSize(1);
        assertThat(events.completed).hasSize(1);
    }

    private ReactAgentSessionAdapter newAdapter(ChatModel model, List<ToolCallback> tools, int maxResultChars) {
        return newAdapter(model, tools, maxResultChars, 32);
    }

    private ReactAgentSessionAdapter newAdapter(
            ChatModel model, List<ToolCallback> tools, int maxResultChars,
            int maxSteps
    ) {
        var adapter = new ReactAgentSessionAdapter(
                (ChatModelProvider) () -> new ChatModelHandle(model, "test-provider", "test-model"),
                redisUrl, "", 65_432, 32_768, 0.1, maxResultChars, tools,
                maxSteps);
        adapters.add(adapter);
        return adapter;
    }

    private ReactAgentSessionAdapter newAdapter(
            ChatModel model, List<ToolCallback> tools, int maxResultChars,
            int maxSteps, java.time.Duration toolExecutionTimeout
    ) {
        var adapter = new ReactAgentSessionAdapter(
                (ChatModelProvider) () -> new ChatModelHandle(model, "test-provider", "test-model"),
                redisUrl, "", 65_432, 32_768, 0.1, maxResultChars, tools,
                maxSteps, toolExecutionTimeout);
        adapters.add(adapter);
        return adapter;
    }

    private ReactAgentSessionAdapter newCodebaseAdapter(
            ChatModel model, CodebaseService codebase, RepositoryEvidenceService evidence
    ) {
        List<ToolCallback> productionTools = CodebaseToolCallback.productionTools(
                new ObjectMapper(), codebase, evidence);
        var adapter = new ReactAgentSessionAdapter(
                (ChatModelProvider) () -> new ChatModelHandle(model, "test-provider", "test-model"),
                new RedissonClientProvider(redisUrl, "", true),
                65_432, 32_768, 0.1, 200_000,
                List.of(), productionTools,
                4, 32,
                64, 32_768, 4_096,
                Duration.ofHours(24), 3,
                2, 1, Duration.ofSeconds(60),
                2, 131_072L, Duration.ofSeconds(120),
                codebase, evidence, 16, 65_536);
        adapters.add(adapter);
        return adapter;
    }

    private static AgentResult completeSync(ReactAgentSessionAdapter adapter, RecordingListener events, AgentRequest request) {
        adapter.stream(request, events);
        if (events.error() != null) {
            throw events.error();
        }
        if (events.result() == null) {
            throw new AssertionError("流式调用未完成");
        }
        return events.result();
    }

    // RedisSaver 内部的实际线程身份；复用路径保持不变，release 后重建会更换
    private static String activeThreadId(String threadId) {
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient client = Redisson.create(config);
        try {
            return (String) client.getMap("graph:thread:meta:" + threadId).get("thread_id");
        } finally {
            client.shutdown();
        }
    }

    private static ToolResponseMessage toolResponseOf(List<Message> messages) {
        return messages.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("模型调用中缺少 tool result 消息"));
    }

    // 系统提示由框架注入，断言时只比较模型可见的 user / assistant 消息
    private static List<Message> visible(List<Message> messages) {
        return messages.stream()
                .filter(message -> message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT)
                .toList();
    }

    private static List<AgentMessage> userList(String text) {
        return List.of(new AgentMessage(AgentMessage.Role.USER, text));
    }

    private static Message user(String text) {
        return UserMessage.builder().text(text).build();
    }

    /** 两个显式只读工具：用闸门控制真实并行调用和可重复的反向完成顺序。 */
    private static final class ParallelGateTool {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch allowLeft = new CountDownLatch(1);
        final CountDownLatch allowRight = new CountDownLatch(1);
        final List<String> completionOrder = new CopyOnWriteArrayList<>();
        final GateTool left;
        final GateTool right;

        ParallelGateTool() {
            this("parallel_left", "parallel_right");
        }

        ParallelGateTool(String leftName, String rightName) {
            left = new GateTool(leftName, allowLeft);
            right = new GateTool(rightName, allowRight);
        }

        private final class GateTool implements ParallelSafeToolCallback {
            private final String name;
            private final CountDownLatch permit;
            final CountDownLatch returned = new CountDownLatch(1);
            final List<String> arguments = new CopyOnWriteArrayList<>();

            private GateTool(String name, CountDownLatch permit) {
                this.name = name;
                this.permit = permit;
            }

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name).description("Gate 只读工具")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String input) {
                arguments.add(input);
                entered.countDown();
                try {
                    if (!permit.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并行 Gate 未收到放行信号");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("并行 Gate 被中断", ex);
                }
                completionOrder.add(name);
                try {
                    return name + " result";
                } finally {
                    returned.countDown();
                }
            }
        }
    }

    /** 阻塞工具：框架超时返回错误 ToolResponse，真实同步回调稍后才释放。 */
    private static final class BlockingTool implements ParallelSafeToolCallback {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("blocking_tool")
                    .description("Gate 阻塞工具").inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String input) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                return "迟到结果";
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return "被中断";
            } finally {
                returned.countDown();
            }
        }
    }

    private static final class FastTool implements ParallelSafeToolCallback {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("fast_tool")
                    .description("Gate 快速只读工具").inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String input) {
            entered.countDown();
            try {
                return "快速结果";
            } finally {
                returned.countDown();
            }
        }
    }

    private static final class ParallelTimeoutTools {
        final BlockingTool blocking = new BlockingTool();
        final FastTool fast = new FastTool();
    }

    private static final class ParallelCallingChatModel implements ChatModel {
        final List<List<Message>> calls = new CopyOnWriteArrayList<>();
        private final String leftName;
        private final String rightName;
        private final String leftArguments;
        private final String rightArguments;

        ParallelCallingChatModel() {
            this("parallel_left", "parallel_right", "{}", "{}");
        }

        ParallelCallingChatModel(
                String leftName, String rightName, String leftArguments, String rightArguments
        ) {
            this.leftName = leftName;
            this.rightName = rightName;
            this.leftArguments = leftArguments;
            this.rightArguments = rightArguments;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = new ArrayList<>(prompt.getInstructions());
            calls.add(instructions);
            boolean hasResults = instructions.stream().anyMatch(ToolResponseMessage.class::isInstance);
            if (!hasResults) {
                AssistantMessage toolCalls = AssistantMessage.builder()
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("parallel-call-left", "function",
                                        leftName, leftArguments),
                                new AssistantMessage.ToolCall("parallel-call-right", "function",
                                        rightName, rightArguments)))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCalls)));
            }
            var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(4, 5, 9)).build();
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("并行结果已按调用顺序收集。").build())), metadata);
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    private static final class SingleBlockingToolModel implements ChatModel {
        final List<List<Message>> calls = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = new ArrayList<>(prompt.getInstructions());
            calls.add(instructions);
            boolean hasResults = instructions.stream().anyMatch(ToolResponseMessage.class::isInstance);
            if (!hasResults) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "blocking-call", "function", "blocking_tool", "{}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("慢工具已收束为安全结果。").build())));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    private static final class ParallelTimeoutCallingChatModel implements ChatModel {
        final List<List<Message>> calls = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = new ArrayList<>(prompt.getInstructions());
            calls.add(instructions);
            boolean hasResults = instructions.stream().anyMatch(ToolResponseMessage.class::isInstance);
            if (!hasResults) {
                AssistantMessage toolCalls = AssistantMessage.builder()
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("parallel-timeout-call", "function",
                                        "blocking_tool", "{}"),
                                new AssistantMessage.ToolCall("parallel-fast-call", "function",
                                        "fast_tool", "{}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCalls)));
            }
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("并行工具均已安全收束。").build())));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    /** 记录工具生命周期事件与模型终态；失败抛出由调用方决定。 */
    private static final class RecordingListener implements AgentStreamListener {

        private final List<String> deltas = new CopyOnWriteArrayList<>();
        private final List<String> reasoning = new CopyOnWriteArrayList<>();
        private final List<AgentToolStarted> started = new CopyOnWriteArrayList<>();
        private final List<AgentToolCompleted> completed = new CopyOnWriteArrayList<>();
        private final List<AgentToolFailed> failed = new CopyOnWriteArrayList<>();
        private final CountDownLatch parallelRightCompleted = new CountDownLatch(1);
        private volatile AgentResult result;
        private volatile AgentExecutionException error;

        @Override
        public void onDelta(String delta) {
            deltas.add(delta);
        }

        @Override
        public void onReasoningDelta(String delta) {
            reasoning.add(delta);
        }

        @Override
        public void onComplete(AgentResult complete) {
            result = complete;
        }

        @Override
        public void onError(AgentExecutionException err) {
            error = err;
        }

        @Override
        public void onToolStarted(AgentToolStarted event) {
            started.add(event);
        }

        @Override
        public void onToolCompleted(AgentToolCompleted event) {
            completed.add(event);
            if ("parallel_right".equals(event.toolName())) {
                parallelRightCompleted.countDown();
            }
        }

        @Override
        public void onToolFailed(AgentToolFailed event) {
            failed.add(event);
        }

        AgentResult result() {
            return result;
        }

        AgentExecutionException error() {
            return error;
        }
    }

    /**
     * 测试专用只读搜索工具：记录每次调用收到的原始参数；
     * 可通过构造开关抛出异常或返回超长结果。
     */
    private static final class RecordingSearchTool implements ToolCallback {

        static final String NAME = "search_test_tool";

        private final List<String> calls = new CopyOnWriteArrayList<>();

        private final String name;

        private final boolean throwOnCall;

        private final String resultText;

        RecordingSearchTool() {
            this(NAME, false, "检索命中：SalmonMind 支持本地文档问答，混合召回与引用。");
        }

        RecordingSearchTool(boolean throwOnCall) {
            this(NAME, throwOnCall, null);
        }

        RecordingSearchTool(boolean throwOnCall, String resultText) {
            this(NAME, throwOnCall, resultText);
        }

        RecordingSearchTool(String name, boolean throwOnCall, String resultText) {
            this.name = name;
            this.throwOnCall = throwOnCall;
            this.resultText = resultText;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("只读测试搜索工具：按查询返回固定短结果")
                    .inputSchema("""
                            {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            calls.add(toolInput);
            if (throwOnCall) {
                throw new IllegalStateException("搜索服务不可用：gate 测试注入异常");
            }
            return resultText;
        }
    }

    /**
     * 代码库 Stage 的确定性模型：不发送空参数选择，而是直接使用当前 Run 的 Active
     * 快照完成定位、源码读取、缺口补读和第二次调用链暂存，验证真实 ReactAgent Tool Loop
     * 能根据结构化失败继续修复，而不是在第一次 stage 失败后提前结束。
     */
    static final class CodebaseFlowChatModel implements ChatModel {

        static final String FINAL_ANSWER = "已根据当前仓库的两段源码整理入口到服务流程。";

        private final List<List<Message>> calls = new CopyOnWriteArrayList<>();
        private final List<String> toolNames = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = new ArrayList<>(prompt.getInstructions());
            calls.add(instructions);
            int toolResultCount = instructions.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .mapToInt(message -> message.getResponses().size())
                    .sum();
            return switch (toolResultCount) {
                case 0 -> tool("codebase-list-001", "list_repository_directory",
                        "{\"path\":\"src\",\"limit\":20}");
                case 1 -> tool("codebase-read-001", "read_repository_file",
                        "{\"path\":\"src/Entry.java\",\"startLine\":1,\"lineCount\":2}");
                case 2 -> tool("codebase-read-002", "read_repository_file",
                        "{\"path\":\"src/Service.java\",\"startLine\":1,\"lineCount\":2}");
                case 3 -> tool("codebase-stage-001", "stage_call_chain", stageArguments());
                case 4 -> tool("codebase-read-003", "read_repository_file",
                        "{\"path\":\"src/Service.java\",\"startLine\":2,\"lineCount\":1}");
                case 5 -> tool("codebase-stage-002", "stage_call_chain", stageArguments());
                default -> finalAnswer();
            };
        }

        private String stageArguments() {
            return """
                        {"name":"入口到服务","nodes":[
                          {"key":"entry","language":"java","qualifiedSymbol":"Demo.enter","signature":"void enter()","path":"src/Entry.java","startLine":1,"endLine":2,"summary":"入口"},
                          {"key":"service","language":"java","qualifiedSymbol":"Demo.run","signature":"void run()","path":"src/Service.java","startLine":1,"endLine":2,"summary":"服务"}
                        ],"edges":[{"from":"entry","to":"service"}]}
                        """;
        }

        private ChatResponse tool(String id, String name, String arguments) {
            toolNames.add(name);
            AssistantMessage message = AssistantMessage.builder()
                    .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                    .properties(Map.of("reasoningContent", "按有界顺序读取当前仓库证据。"))
                    .build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        private ChatResponse finalAnswer() {
            AssistantMessage message = AssistantMessage.builder().content(FINAL_ANSWER).build();
            return new ChatResponse(List.of(new Generation(message)),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(120, 20, 140)).build());
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    /**
     * 确定性 ChatModel：首次未看到本 Call ID 的 tool result 时返回带稳定 Tool Call ID
     * 的工具调用，看到后才返回最终回答与确定性 usage。记录每次收到的模型可见消息。
     */
    static final class ToolCallingChatModel implements ChatModel {

        static final String TOOL_CALL_ID = "call-001";

        static final String TOOL_NAME = RecordingSearchTool.NAME;

        static final String FINAL_ANSWER = "根据检索结果，SalmonMind 支持本地文档问答。";

        private final String finalAnswer;
        private final String toolName;
        private final String toolArguments;

        ToolCallingChatModel() {
            this(FINAL_ANSWER, RecordingSearchTool.NAME, "{\"query\":\"salmon\"}");
        }

        ToolCallingChatModel(String finalAnswer) {
            this(finalAnswer, RecordingSearchTool.NAME, "{\"query\":\"salmon\"}");
        }

        ToolCallingChatModel(String finalAnswer, String toolName) {
            this(finalAnswer, toolName, "{\"query\":\"salmon\"}");
        }

        ToolCallingChatModel(String finalAnswer, String toolName, String toolArguments) {
            this.finalAnswer = finalAnswer;
            this.toolName = toolName;
            this.toolArguments = toolArguments;
        }

        private final List<List<Message>> calls = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = new ArrayList<>(prompt.getInstructions());
            calls.add(instructions);
            boolean sawToolResult = instructions.stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .anyMatch(response -> TOOL_CALL_ID.equals(response.id()));
            if (!sawToolResult) {
                var toolCallMessage = AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                TOOL_CALL_ID, "function", toolName, toolArguments)))
                        .properties(Map.of("reasoningContent", "需要先查询资料。"))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCallMessage)));
            }
            var answer = AssistantMessage.builder()
                    .content(finalAnswer)
                    .properties(Map.of("reasoningContent", "资料足够，可以作答。"))
                    .build();
            var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(42, 7, 49)).build();
            return new ChatResponse(List.of(new Generation(answer)), metadata);
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            // Gate 发现：框架要求 ChatModel 默认选项与 Agent chatOptions 同类型，
            // 否则 build 时 Jackson merge 对 DefaultChatOptions（无 @JsonProperty）抛错
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            // 锁定框架的 LLM 节点经 ChatClient 走 stream 通道，确定性模型以单响应 Flux 实现
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    /** 第二次工具请求在累计结果预算耗尽后仍可被模型收束为普通回答。 */
    static final class BudgetCallingChatModel implements ChatModel {

        static final String FINAL_ANSWER = "工具上下文已受限，我使用已有信息回答。";
        private final List<List<Message>> calls = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = new ArrayList<>(prompt.getInstructions());
            calls.add(instructions);
            long resultCount = instructions.stream().filter(ToolResponseMessage.class::isInstance).count();
            if (resultCount < 2) {
                String id = "budget-call-" + (resultCount + 1);
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                id, "function", RecordingSearchTool.NAME, "{\"query\":\"salmon\"}")))
                        .build())));
            }
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content(FINAL_ANSWER).build())));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }

    /** 忽略工具结果、持续请求工具的确定性模型，用于证明公开 max-steps 硬上限。 */
    static final class LoopingToolChatModel implements ChatModel {

        private final List<List<Message>> calls = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> instructions = new ArrayList<>(prompt.getInstructions());
            calls.add(instructions);
            String id = "loop-call-" + calls.size();
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            id, "function", RecordingSearchTool.NAME, "{\"query\":\"loop\"}")))
                    .build())));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder().build();
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
            return reactor.core.publisher.Flux.just(call(prompt));
        }
    }
}
