package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tool.ToolCancelledException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuyu.salmonmind.agent.api.AgentStreamListener;
import com.yuyu.salmonmind.agent.api.AgentToolCompleted;
import com.yuyu.salmonmind.agent.api.AgentToolFailed;
import com.yuyu.salmonmind.agent.api.AgentToolOutcomeDetail;
import com.yuyu.salmonmind.agent.api.AgentToolStarted;
import com.yuyu.salmonmind.agent.api.AgentToolRequestDetail;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台拥有的工具生命周期拦截器：把 Spring AI Alibaba 的 ToolInterceptor 钩子映射为
 * {@code agent::api} 的 started/completed/failed 平台事件，并在此处完成调用次数、结果大小
 * 与累计上下文三类 Gate 控制点：
 *
 * <ul>
 *   <li>每个 Tool Call 的 started 后至多一次 completed 或 failed：结果以
 *       {@link ToolCallResponse#isError()} 与异常为失败依据，二者互斥。</li>
 *   <li>工具结果在回到模型上下文前按 {@code maxToolResultChars} 有界截断，
 *       不允许超长原始结果直接进入下一轮模型 Prompt；当前 Run 还共享一个 token 总预算。</li>
 * </ul>
 *
 * <p>拦截器通过 {@link #LISTENER_METADATA_KEY} 从 RunnableConfig metadata 中取出
 * 当前流的 {@link AgentStreamListener}：config 由 Adapter 在每次 stream 时构建并
 * 随图执行流转，因此同一 Agent 上的并发流互不干扰。没有挂载 listener 时为空操作。
 *
 * <p>异常采用与框架 ToolErrorInterceptor 相同的处理模式：捕获后转为错误
 * ToolCallResponse 送回 Agent 循环，而不是抛出——保证工具失败只产生一次 failed 观察，
 * 并最终收束为唯一一次 Agent complete 或 error，不产生双终态。
 */
class ToolLifecycleInterceptor extends ToolInterceptor {

    /** RunnableConfig metadata 中挂载当前流监听器的键。 */
    static final String LISTENER_METADATA_KEY = "salmon:agent:tool-listener";

    /** 工具失败的标准错误码：所有未分类的异常统一使用该稳定错误码。 */
    private static final String ERROR_CODE_TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED";

    /** 每次 Agent stream 独立的工具预算 metadata 键。 */
    static final String INVOCATION_BUDGET_METADATA_KEY = "salmon:agent:tool-budget";
    static final String RESULT_BUDGET_METADATA_KEY = "salmon:agent:tool-result-budget";
    static final String GOVERNOR_METADATA_KEY = "salmon:agent:tool-governor";
    static final String LOCAL_SEARCH_ALLOWED_METADATA_KEY = "salmon:agent:local-search-allowed";
    static final String WEB_SEARCH_ALLOWED_METADATA_KEY = "salmon:agent:web-search-allowed";
    static final String CODEBASE_INVOCATION_BUDGET_METADATA_KEY = "salmon:agent:codebase-tool-budget";
    static final String CODEBASE_RESULT_BUDGET_METADATA_KEY = "salmon:agent:codebase-result-budget";
    static final String CODEBASE_ACCESS_ALLOWED_METADATA_KEY = "salmon:agent:codebase-allowed";
    static final String TOOL_CALL_BUDGET_EXCEEDED = "TOOL_CALL_BUDGET_EXCEEDED";
    static final String TOOL_CONTEXT_BUDGET_EXCEEDED = "TOOL_CONTEXT_BUDGET_EXCEEDED";
    static final String TOOL_CONCURRENCY_LIMIT_REACHED = "TOOL_CONCURRENCY_LIMIT_REACHED";
    static final String TOOL_EXECUTION_TIMEOUT = "TOOL_EXECUTION_TIMEOUT";
    /** 保留旧包内命名，调用次数预算的稳定语义已单独命名。 */
    static final String TOOL_BUDGET_EXCEEDED = TOOL_CALL_BUDGET_EXCEEDED;
    private static final long MIN_TOOL_RESULT_TOKENS = 64L;
    private static final long TOOL_MESSAGE_OVERHEAD = 8L;
    private static final String TOOL_CALL_BUDGET_RESULT =
            "{\"status\":\"UNAVAILABLE\",\"reason\":\"TOOL_CALL_BUDGET_EXCEEDED\","
                    + "\"sourceKind\":\"UNKNOWN\",\"items\":[]}";
    private static final String TOOL_CONTEXT_BUDGET_RESULT =
            "{\"status\":\"UNAVAILABLE\",\"reason\":\"TOOL_CONTEXT_BUDGET_EXCEEDED\","
                    + "\"sourceKind\":\"UNKNOWN\",\"items\":[]}";

    private final int maxToolResultChars;
    private final int codebaseMaxToolResultChars;
    private final ObjectMapper objectMapper;

    ToolLifecycleInterceptor(int maxToolResultChars) {
        this(maxToolResultChars, maxToolResultChars, new ObjectMapper());
    }

    ToolLifecycleInterceptor(int maxToolResultChars, ObjectMapper objectMapper) {
        this(maxToolResultChars, maxToolResultChars, objectMapper);
    }

    ToolLifecycleInterceptor(int maxToolResultChars, int codebaseMaxToolResultChars, ObjectMapper objectMapper) {
        this.maxToolResultChars = Math.max(0, maxToolResultChars);
        this.codebaseMaxToolResultChars = Math.max(0, codebaseMaxToolResultChars);
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "tool-lifecycle-observer";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        AgentStreamListener listener = listenerOf(request);
        boolean codebaseTool = CodebaseToolCallback.isCodebaseToolName(request.getToolName());
        long startedNanos = System.nanoTime();
        AgentToolRequestDetail requestDetail = ToolRequestDetailProjector.project(
                request.getToolName(), request.getArguments(), objectMapper);
        if (!runOpen(request)) {
            return ToolCallResponse.error(
                    request.getToolCallId(), request.getToolName(), "TOOL_EXECUTION_AFTER_RUN_TERMINAL");
        }
        if (listener != null) {
            listener.onToolStarted(new AgentToolStarted(
                    request.getToolCallId(), request.getToolName(),
                    requestDetail == null ? toolStartSummary(request.getToolName()) : requestDetail.querySummary(),
                    requestDetail));
        }
        if (codebaseTool && !codebaseAccessAllowed(request)) {
            if (listener != null) {
                emitToolFailed(listener, failedEvent(request, startedNanos,
                        "CODEBASE_ACCESS_DISABLED", "用户已禁止读取本地代码", null));
            }
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                    codebaseUnavailableResult(request, "CODEBASE_ACCESS_DISABLED"));
        }
        BudgetAcquisition acquisition = acquireBudget(request, codebaseTool);
        if (!acquisition.acquired()) {
            if (listener != null) {
                emitToolFailed(listener, failedEvent(request, startedNanos,
                        acquisition.reason(), "已达到本轮工具调用上限", null));
            }
            // 预算耗尽仍返回可被模型理解的结构化结果；不抛异常，避免工具失败制造 Run 双终态。
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), budgetResult(request, acquisition.reason()));
        }
        if (isLocalTool(request.getToolName()) && !localSearchAllowed(request)) {
            if (listener != null) {
                emitToolFailed(listener, failedEvent(request, startedNanos,
                        "LOCAL_SEARCH_DISABLED", "用户已禁止本地检索", null));
            }
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                    disabledLocalSearchResult());
        }
        if (isWebTool(request.getToolName()) && !webSearchAllowed(request)) {
            if (listener != null) {
                emitToolFailed(listener, failedEvent(request, startedNanos,
                        "WEB_SEARCH_DISABLED", "用户已禁止联网", null));
            }
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                    disabledWebSearchResult(request.getToolName()));
        }
        ToolResultBudget resultBudget = resultBudgetOf(request, codebaseTool);
        ToolResultBudget.Reservation reservation = resultBudget == null ? null : resultBudget.reserve();
        if (resultBudget != null && reservation == null) {
            if (listener != null) {
                emitToolFailed(listener, failedEvent(request, startedNanos,
                        TOOL_CONTEXT_BUDGET_EXCEEDED, "已达到本轮工具结果上下文预算", null));
            }
            // 预算不足时不执行 handler，外部 Provider 不会被访问。
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                    budgetResult(request, TOOL_CONTEXT_BUDGET_EXCEEDED));
        }
        ToolExecutionGovernor governor = governorOf(request);
        ToolExecutionGovernor.Permit permit = governor == null ? null : governor.tryAcquire(request.getToolName());
        if (governor != null && permit == null) {
            if (resultBudget != null) {
                resultBudget.cancel(reservation);
            }
            if (listener != null) {
                emitToolFailed(listener, failedEvent(request, startedNanos,
                        TOOL_CONCURRENCY_LIMIT_REACHED, "当前工具并发已达到上限", null));
            }
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                    "TOOL_CONCURRENCY_LIMIT_REACHED");
        }
        try {
            ToolCallResponse raw = handler.call(request);
            if (codebaseTool) {
                raw = rebuild(raw, withCodebaseBudget(request, raw.getResult()));
            }
            // ReactAgent 正式 timeout 可能让框架先收束，而同步 Tool 线程稍后才返回；
            // 终态 Fence 关闭后不再触碰 Source Registry、预算或 SSE。
            if (!runOpen(request)) {
                if (resultBudget != null) {
                    resultBudget.cancel(reservation);
                }
                return ToolCallResponse.error(
                        request.getToolCallId(), request.getToolName(), "TOOL_EXECUTION_AFTER_RUN_TERMINAL");
            }
            BoundResult bounded = boundResultSize(raw, request, resultBudget, reservation);
            if (bounded.budgetExceeded()) {
                if (resultBudget != null) {
                    resultBudget.cancel(reservation);
                }
                rollbackSource(request, bounded.decoration());
                if (listener != null) {
                    emitToolFailed(listener, failedEvent(request, startedNanos,
                            TOOL_CONTEXT_BUDGET_EXCEEDED, "工具结果超过本轮上下文预算",
                            bounded.decoration()));
                }
                return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                        budgetResult(request, TOOL_CONTEXT_BUDGET_EXCEEDED));
            }
            if (resultBudget != null && !resultBudget.commit(reservation, bounded.estimatedTokens())) {
                rollbackSource(request, bounded.decoration());
                if (listener != null) {
                    emitToolFailed(listener, failedEvent(request, startedNanos,
                            TOOL_CONTEXT_BUDGET_EXCEEDED, "工具结果超过本轮上下文预算",
                            bounded.decoration()));
                }
                return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                        budgetResult(request, TOOL_CONTEXT_BUDGET_EXCEEDED));
            }
            ToolCallResponse response = bounded.response();
            // 只有最终裁剪结果已经成功提交到本 Run 的结果预算后，才把 ReadFile 行登记为
            // 可用于 prepare 的证据；原始超长结果和预算失败结果永远不能形成调用链。
            if (codebaseTool && !response.isError()) {
                registerCodebaseEvidence(request, response.getResult());
            }
            RunSourceRegistry.Decoration source = bounded.decoration();
            long durationMillis = elapsedMillis(startedNanos);
            if (listener != null) {
                if (response.isError() || isStructuredUnavailable(response.getResult())) {
                    String errorCode = isTimeoutResponse(response)
                            ? TOOL_EXECUTION_TIMEOUT : stableErrorCode(response);
                    emitToolFailed(listener, failedEventWithDuration(request, durationMillis, errorCode,
                            safeFailureSummary(errorCode), source, bounded.resultTruncated()));
                } else {
                    emitToolCompleted(listener, new AgentToolCompleted(
                            response.getToolCallId(), response.getToolName(),
                            outcomeDetail(source, durationMillis, null, bounded.resultTruncated()), null));
                }
            }
            return response;
        } catch (RuntimeException ex) {
            if (resultBudget != null) {
                resultBudget.cancel(reservation);
            }
            // 与框架 ToolErrorInterceptor 同款模式：异常转错误结果，保持循环单终态
            if (listener != null) {
                String errorCode = isTimeout(ex) ? TOOL_EXECUTION_TIMEOUT : ERROR_CODE_TOOL_EXECUTION_FAILED;
                emitToolFailed(listener, failedEvent(request, startedNanos, errorCode,
                        safeFailureSummary(errorCode), null));
            }
            String errorCode = isTimeout(ex) ? TOOL_EXECUTION_TIMEOUT : ERROR_CODE_TOOL_EXECUTION_FAILED;
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(),
                    codebaseTool ? codebaseUnavailableResult(request, errorCode) : errorCode);
        } finally {
            if (permit != null) {
                permit.close();
            }
        }
    }

    /** 从当前 ToolCallRequest 的执行上下文 config 中取回挂载的监听器；未挂载返回 null。 */
    private static void emitToolCompleted(AgentStreamListener listener, AgentToolCompleted event) {
        if (listener instanceof RunTraceCollector trace) {
            trace.onToolCompletedOrdered(event, trace.reserveTerminalSequence(event.toolCallId()));
        } else {
            listener.onToolCompleted(event);
        }
    }

    private static void emitToolFailed(AgentStreamListener listener, AgentToolFailed event) {
        if (listener instanceof RunTraceCollector trace) {
            trace.onToolFailedOrdered(event, trace.reserveTerminalSequence(event.toolCallId()));
        } else {
            listener.onToolFailed(event);
        }
    }

    private static AgentToolFailed failedEvent(
            ToolCallRequest request,
            long startedNanos,
            String errorCode,
            String safeMessage,
            RunSourceRegistry.Decoration source
    ) {
        return failedEventWithDuration(request, elapsedMillis(startedNanos), errorCode, safeMessage, source, false);
    }

    private static AgentToolFailed failedEventWithDuration(
            ToolCallRequest request,
            long durationMillis,
            String errorCode,
            String safeMessage,
            RunSourceRegistry.Decoration source
    ) {
        return failedEventWithDuration(request, durationMillis, errorCode, safeMessage, source, false);
    }

    private static AgentToolFailed failedEventWithDuration(
            ToolCallRequest request,
            long durationMillis,
            String errorCode,
            String safeMessage,
            RunSourceRegistry.Decoration source,
            boolean resultTruncated
    ) {
        return new AgentToolFailed(request.getToolCallId(), request.getToolName(),
                outcomeDetail(request.getToolName(), durationMillis, errorCode, source,
                        resultTruncated || source != null && source.resultTruncated()),
                errorCode, safeMessage);
    }

    private static AgentToolOutcomeDetail outcomeDetail(
            RunSourceRegistry.Decoration source,
            long durationMillis,
            String errorCode,
            boolean resultTruncated
    ) {
        return outcomeDetail(null, durationMillis, errorCode, source, resultTruncated);
    }

    /** 为超时等未进入 Tool Result 的稳定失败补出可由工具名证明的终态字段。 */
    static AgentToolOutcomeDetail outcomeDetail(
            String toolName,
            long durationMillis,
            String errorCode,
            RunSourceRegistry.Decoration source,
            boolean resultTruncated
    ) {
        String provider = source == null ? providerForTool(toolName) : source.provider();
        AgentToolOutcomeDetail.ResultStatus status = source == null
                ? (errorCode == null ? null : isSearchTool(toolName)
                        ? AgentToolOutcomeDetail.ResultStatus.UNAVAILABLE : null)
                : source.resultStatus();
        String reason = source == null ? errorCode : source.stableReasonCode();
        Integer sourceCount = source == null ? null : source.sourceCount();
        boolean degraded = source != null && source.degraded();
        boolean bounded = resultTruncated || source != null && source.resultTruncated();
        return new AgentToolOutcomeDetail(provider, status, reason, sourceCount, durationMillis, degraded, bounded);
    }

    private static boolean isSearchTool(String toolName) {
        return isLocalTool(toolName) || isWebTool(toolName) || CodebaseToolCallback.isCodebaseToolName(toolName);
    }

    private static String providerForTool(String toolName) {
        if (isLocalTool(toolName)) {
            return "LOCAL";
        }
        if (CodebaseToolCallback.isCodebaseToolName(toolName)) {
            return "CODEBASE";
        }
        if ("search_web_bocha".equals(toolName)) {
            return "BOCHA";
        }
        if ("search_web_searchapi".equals(toolName)) {
            return "SEARCH_API";
        }
        return null;
    }

    private static AgentStreamListener listenerOf(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(LISTENER_METADATA_KEY))
                .filter(AgentStreamListener.class::isInstance)
                .map(AgentStreamListener.class::cast)
                .orElse(null);
    }

    /** 从当前执行上下文取得本次 stream 的有界计数器；没有 metadata 时保持测试兼容。 */
    private static InvocationBudget budgetOf(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(INVOCATION_BUDGET_METADATA_KEY))
                .filter(InvocationBudget.class::isInstance)
                .map(InvocationBudget.class::cast)
                .orElse(null);
    }

    private static InvocationBudget budgetOf(ToolCallRequest request, boolean codebaseTool) {
        String metadataKey = codebaseTool
                ? CODEBASE_INVOCATION_BUDGET_METADATA_KEY : INVOCATION_BUDGET_METADATA_KEY;
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(metadataKey))
                .filter(InvocationBudget.class::isInstance)
                .map(InvocationBudget.class::cast)
                .orElse(null);
    }

    private static boolean webSearchAllowed(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(WEB_SEARCH_ALLOWED_METADATA_KEY))
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(true);
    }

    private static boolean localSearchAllowed(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(LOCAL_SEARCH_ALLOWED_METADATA_KEY))
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(true);
    }

    private static boolean codebaseAccessAllowed(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(CODEBASE_ACCESS_ALLOWED_METADATA_KEY))
                .filter(Boolean.class::isInstance)
                .map(Boolean.class::cast)
                .orElse(true);
    }

    private static boolean isLocalTool(String toolName) {
        return "search_local_knowledge".equals(toolName);
    }

    private static boolean isWebTool(String toolName) {
        return "search_web_bocha".equals(toolName) || "search_web_searchapi".equals(toolName);
    }

    private static String disabledWebSearchResult(String toolName) {
        String provider = "search_web_bocha".equals(toolName) ? "BOCHA" : "SEARCH_API";
        return "{\"status\":\"UNAVAILABLE\",\"reason\":\"USER_DISABLED\","
                + "\"sourceKind\":\"WEB\",\"provider\":\"" + provider + "\",\"items\":[]}";
    }

    private static String disabledLocalSearchResult() {
        return "{\"status\":\"UNAVAILABLE\",\"reason\":\"USER_DISABLED\","
                + "\"sourceKind\":\"LOCAL\",\"provider\":\"LOCAL\",\"items\":[]}";
    }

    private String codebaseUnavailableResult(ToolCallRequest request, String reason) {
        String toolName = request.getToolName();
        return withCodebaseBudget(request, "{\"status\":\"UNAVAILABLE\",\"reason\":\"" + reason
                + "\",\"sourceKind\":\"CODEBASE\",\"provider\":\"CODEBASE\",\"operation\":\""
                + toolName + "\",\"items\":[]}");
    }

    private String budgetResult(ToolCallRequest request, String reason) {
        if (CodebaseToolCallback.isCodebaseToolName(request.getToolName())) {
            return codebaseUnavailableResult(request, reason);
        }
        return TOOL_CONTEXT_BUDGET_EXCEEDED.equals(reason)
                ? TOOL_CONTEXT_BUDGET_RESULT : TOOL_CALL_BUDGET_RESULT;
    }

    /** 工具结果进入模型上下文前的有界控制点；来源结果必须按完整 item 边界裁剪。 */
    private BoundResult boundResultSize(
            ToolCallResponse response,
            ToolCallRequest request,
            ToolResultBudget resultBudget,
            ToolResultBudget.Reservation reservation
    ) {
        String result = response.getResult();
        int maxResultChars = CodebaseToolCallback.isCodebaseToolName(request.getToolName())
                ? codebaseMaxToolResultChars : maxToolResultChars;
        RunSourceRegistry registry = sourceRegistryOf(request);
        long tokenLimit = resultBudget == null
                ? Long.MAX_VALUE : resultBudget.availableForCurrent(reservation);
        if (registry != null) {
            RunSourceRegistry.Decoration decoration = registry.decorate(
                    result, maxResultChars, tokenLimit, request.getToolCallId());
            if (decoration != null) {
                return new BoundResult(rebuild(response, decoration.result()), decoration,
                        decoration.estimatedTokens(), !decoration.withinTokenBudget(),
                        decoration.resultTruncated());
            }
        }
        if (result == null) {
            return new BoundResult(response, null, estimateToolResultTokens(""), false, false);
        }
        String bounded = result.length() <= maxResultChars
                ? result : result.substring(0, maxResultChars);
        boolean resultTruncated = !bounded.equals(result);
        long estimatedTokens = estimateToolResultTokens(bounded);
        if (estimatedTokens > tokenLimit) {
            bounded = truncateToTokens(bounded, tokenLimit);
            resultTruncated = true;
            estimatedTokens = estimateToolResultTokens(bounded);
            if (estimatedTokens > tokenLimit) {
                return new BoundResult(response, null, estimatedTokens, true, true);
            }
        }
        if (bounded.equals(result)) {
            return new BoundResult(response, null, estimatedTokens, false, resultTruncated);
        }
        ToolCallResponse.Builder builder = ToolCallResponse.builder()
                .toolCallId(response.getToolCallId())
                .toolName(response.getToolName())
                .content(bounded);
        if (response.getStatus() != null) {
            builder.status(response.getStatus());
        }
        if (response.getMetadata() != null) {
            builder.metadata(response.getMetadata());
        }
        return new BoundResult(builder.build(), null, estimatedTokens, false, resultTruncated);
    }

    private static String truncateToTokens(String value, long tokenLimit) {
        if (tokenLimit <= TOOL_MESSAGE_OVERHEAD) {
            return "";
        }
        long contentLimit = tokenLimit - TOOL_MESSAGE_OVERHEAD;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            String candidate = value.substring(0, middle);
            if (estimateTextTokens(candidate) <= contentLimit) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        if (low > 0 && low < value.length() && Character.isHighSurrogate(value.charAt(low - 1))) {
            low--;
        }
        return value.substring(0, low);
    }

    private static ToolCallResponse rebuild(ToolCallResponse response, String content) {
        ToolCallResponse.Builder builder = ToolCallResponse.builder()
                .toolCallId(response.getToolCallId())
                .toolName(response.getToolName())
                .content(content);
        if (response.getStatus() != null) {
            builder.status(response.getStatus());
        }
        if (response.getMetadata() != null) {
            builder.metadata(response.getMetadata());
        }
        return builder.build();
    }

    /**
     * 错误响应优先使用框架 status，但框架统一使用 "error" 表达所有失败：
     * 映射为平台稳定错误码，避免把内部 status 直接暴露为业务错误码。
     */
    private String stableErrorCode(ToolCallResponse response) {
        if (isStructuredUnavailable(response.getResult())) {
            return structuredErrorCode(response.getResult());
        }
        String status = response.getStatus();
        return status == null || status.isBlank() || "error".equalsIgnoreCase(status)
                ? ERROR_CODE_TOOL_EXECUTION_FAILED
                : status;
    }

    /** 框架超时会被转换成 error response，不能只依赖拦截器捕获的异常。 */
    private static boolean isTimeoutResponse(ToolCallResponse response) {
        return containsTimeoutMarker(response.getResult())
                || containsTimeoutMarker(response.getStatus());
    }

    /** 本地 Knowledge Tool 以结构化内容返回可理解失败，不能被误报成成功工具调用。 */
    private static boolean isStructuredUnavailable(String result) {
        return result != null && result.stripLeading().startsWith("{\"status\":\"UNAVAILABLE\"");
    }

    /** 按来源种类保留网页 Provider-aware 错误，不把所有失败压成同一个检索码。 */
    private String structuredErrorCode(String result) {
        try {
            JsonNode root = objectMapper.readTree(result);
            if ("CODEBASE".equals(root.path("sourceKind").asText())) {
                String reason = root.path("reason").asText("CODEBASE_UNAVAILABLE");
                return "USER_DISABLED".equals(reason) ? "CODEBASE_ACCESS_DISABLED" : reason;
            }
            if ("WEB".equals(root.path("sourceKind").asText())) {
                return switch (root.path("reason").asText()) {
                    case "NOT_CONFIGURED" -> "WEB_SEARCH_NOT_CONFIGURED";
                    case "AUTH_FAILED" -> "WEB_SEARCH_AUTH_FAILED";
                    case "RATE_LIMITED" -> "WEB_SEARCH_RATE_LIMITED";
                    case "TIMEOUT" -> "WEB_SEARCH_TIMEOUT";
                    case "USER_DISABLED" -> "WEB_SEARCH_DISABLED";
                    case "INVALID_RESPONSE" -> "WEB_SEARCH_INVALID_RESPONSE";
                    case "PROVIDER_FAILED" -> "WEB_SEARCH_PROVIDER_FAILED";
                    default -> "WEB_SEARCH_PROVIDER_FAILED";
                };
            }
            if ("LOCAL".equals(root.path("sourceKind").asText())
                    && "USER_DISABLED".equals(root.path("reason").asText())) {
                return "LOCAL_SEARCH_DISABLED";
            }
        } catch (Exception ignored) {
            // 结构化前缀已经成立，解析失败时退回稳定通用码
        }
        return "RETRIEVAL_UNAVAILABLE";
    }

    private static RunSourceRegistry sourceRegistryOf(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(RunSourceRegistry.METADATA_KEY))
                .filter(RunSourceRegistry.class::isInstance)
                .map(RunSourceRegistry.class::cast)
                .orElse(null);
    }

    private static void registerCodebaseEvidence(ToolCallRequest request, String result) {
        request.getExecutionContext()
                .flatMap(context -> context.config().metadata(CodebaseRunContext.METADATA_KEY))
                .filter(CodebaseRunContext.class::isInstance)
                .map(CodebaseRunContext.class::cast)
                .ifPresent(context -> context.registerReadFileResult(result));
    }

    private BudgetAcquisition acquireBudget(ToolCallRequest request, boolean codebaseTool) {
        if (codebaseTool) {
            CodebaseBudget codebaseBudget = request.getExecutionContext()
                    .flatMap(context -> context.config().metadata(CODEBASE_INVOCATION_BUDGET_METADATA_KEY))
                    .filter(CodebaseBudget.class::isInstance)
                    .map(CodebaseBudget.class::cast)
                    .orElse(null);
            if (codebaseBudget != null) {
                CodebaseBudget.AcquireResult result = codebaseBudget.acquire(request.getToolName());
                return new BudgetAcquisition(result.acquired(), result.reason());
            }
        }
        InvocationBudget budget = budgetOf(request, codebaseTool);
        if (budget == null) {
            return new BudgetAcquisition(true, null);
        }
        return new BudgetAcquisition(budget.tryAcquire(), TOOL_CALL_BUDGET_EXCEEDED);
    }

    private String withCodebaseBudget(ToolCallRequest request, String result) {
        if (!CodebaseToolCallback.isCodebaseToolName(request.getToolName()) || result == null) {
            return result;
        }
        CodebaseBudget codebaseBudget = request.getExecutionContext()
                .flatMap(context -> context.config().metadata(CODEBASE_INVOCATION_BUDGET_METADATA_KEY))
                .filter(CodebaseBudget.class::isInstance)
                .map(CodebaseBudget.class::cast)
                .orElse(null);
        if (codebaseBudget == null) {
            return result;
        }
        try {
            JsonNode parsed = objectMapper.readTree(result);
            if (parsed == null || !parsed.isObject()) {
                return result;
            }
            ObjectNode envelope = (ObjectNode) parsed.deepCopy();
            CodebaseBudget.Snapshot snapshot = codebaseBudget.snapshot();
            ObjectNode budget = envelope.putObject("budget");
            budget.put("remainingEvidenceCalls", snapshot.remainingEvidenceCalls());
            budget.put("discoveryAllowed", snapshot.discoveryAllowed());
            budget.put("stageAvailable", snapshot.stageAvailable());
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception ignored) {
            return result;
        }
    }

    private static void rollbackSource(ToolCallRequest request, RunSourceRegistry.Decoration decoration) {
        RunSourceRegistry registry = sourceRegistryOf(request);
        if (registry != null) {
            registry.rollback(decoration);
        }
    }

    private static ToolResultBudget resultBudgetOf(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(RESULT_BUDGET_METADATA_KEY))
                .filter(ToolResultBudget.class::isInstance)
                .map(ToolResultBudget.class::cast)
                .orElse(null);
    }

    private static ToolResultBudget resultBudgetOf(ToolCallRequest request, boolean codebaseTool) {
        String metadataKey = codebaseTool
                ? CODEBASE_RESULT_BUDGET_METADATA_KEY : RESULT_BUDGET_METADATA_KEY;
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(metadataKey))
                .filter(ToolResultBudget.class::isInstance)
                .map(ToolResultBudget.class::cast)
                .orElse(null);
    }

    private static ToolExecutionGovernor governorOf(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(GOVERNOR_METADATA_KEY))
                .filter(ToolExecutionGovernor.class::isInstance)
                .map(ToolExecutionGovernor.class::cast)
                .orElse(null);
    }

    private static boolean runOpen(ToolCallRequest request) {
        return request.getExecutionContext()
                .flatMap(context -> context.config().metadata(LISTENER_METADATA_KEY))
                .filter(RunTraceCollector.class::isInstance)
                .map(RunTraceCollector.class::cast)
                .map(trace -> trace.isOpen() && !trace.isToolTerminal(request.getToolCallId()))
                .orElse(true);
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.util.concurrent.CancellationException
                    || current instanceof InterruptedException
                    || current instanceof ToolCancelledException
                    || containsTimeoutMarker(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean containsTimeoutMarker(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("timed out") || normalized.contains("timeout");
    }


    private record BoundResult(
            ToolCallResponse response,
            RunSourceRegistry.Decoration decoration,
            long estimatedTokens,
            boolean budgetExceeded,
            boolean resultTruncated
    ) {
    }

    private record BudgetAcquisition(boolean acquired, String reason) {
    }

    static long estimateToolResultTokens(String text) {
        return estimateTextTokens(text) + TOOL_MESSAGE_OVERHEAD;
    }

    private static long estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 1L;
        }
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1L, (bytes + 1L) / 2L);
    }

    /** 工具开始事件只报告能力状态，任何工具的原始参数都不进入 SSE。 */
    private static String toolStartSummary(String toolName) {
        return switch (toolName) {
            case "search_web_bocha", "search_web_searchapi" -> "网页检索";
            case "select_local_repository" -> "选择本地仓库";
            case "list_repository_directory" -> "浏览仓库目录";
            case "glob_repository_files", "grep_repository" -> "搜索仓库源码";
            case "read_repository_file" -> "读取仓库文件";
            case "git_repository_status" -> "查看 Git 状态";
            case "git_repository_diff" -> "查看 Git 差异";
            case "git_repository_log", "git_repository_show", "git_repository_blame" -> "查看 Git 历史";
            case CallChainToolCallback.NAME -> "整理临时调用链";
            default -> "工具执行中";
        };
    }

    /**
     * 错误展示只由稳定错误码映射，绝不回退到原始工具结果、Provider 响应或异常消息。
     */
    private static String safeFailureSummary(String errorCode) {
        return switch (errorCode) {
            case "WEB_SEARCH_NOT_CONFIGURED" -> "网页搜索尚未配置";
            case "WEB_SEARCH_AUTH_FAILED" -> "网页搜索鉴权失败";
            case "WEB_SEARCH_RATE_LIMITED" -> "网页搜索请求过于频繁";
            case "WEB_SEARCH_TIMEOUT" -> "网页搜索超时";
            case "WEB_SEARCH_INVALID_RESPONSE" -> "网页搜索响应格式异常";
            case "WEB_SEARCH_DISABLED" -> "用户已禁止联网";
            case "LOCAL_SEARCH_DISABLED" -> "用户已禁止本地检索";
            case "WEB_SEARCH_FAILED", "WEB_SEARCH_PROVIDER_FAILED", "RETRIEVAL_UNAVAILABLE" -> "检索服务暂不可用";
            case "CODEBASE_ACCESS_DISABLED" -> "用户已禁止读取本地代码";
            case "REPOSITORY_NOT_SELECTED" -> "尚未选择本地仓库";
            case "REPOSITORY_SELECTION_REQUIRED" -> "需要选择一个本地仓库";
            case "MULTIPLE_REPOSITORIES_NOT_SUPPORTED" -> "一次对话只能绑定一个本地仓库";
            case "REPOSITORY_NOT_FOUND", "REFERENCE_NOT_FOUND" -> "未找到本地仓库";
            case "CALL_CHAIN_DRAFT_INVALID" -> "调用链草稿不合法";
            case "CALL_CHAIN_EVIDENCE_INSUFFICIENT" -> "调用链源码证据不足";
            case "CALL_CHAIN_REPOSITORY_CHANGED" -> "仓库或源码已发生变化";
            case "CALL_CHAIN_REVISION_UPDATE_REQUIRED" -> "节点已变化，需要后续 Revision 支持";
            case "CALL_CHAIN_MATCH_AMBIGUOUS" -> "调用链匹配存在歧义";
            case "CALL_CHAIN_IDENTITY_CONFLICT" -> "调用链版本已变化";
            case CodebaseBudget.DISCOVERY_RESERVED -> "目录发现额度已保留给方法读取";
            case "PATH_OUTSIDE_REPOSITORY" -> "查询路径超出仓库边界";
            case "INVALID_QUERY" -> "代码库查询参数无效";
            case "CODEBASE_UNAVAILABLE", "PATH_NOT_FOUND", "REPOSITORY_UNAVAILABLE",
                    "GIT_NOT_AVAILABLE", "GIT_QUERY_FAILED", "GIT_QUERY_TIMEOUT" -> "本地代码库暂不可用";
            case TOOL_CALL_BUDGET_EXCEEDED -> "已达到本轮工具调用上限";
            case TOOL_CONTEXT_BUDGET_EXCEEDED -> "已达到本轮工具结果上下文预算";
            case TOOL_CONCURRENCY_LIMIT_REACHED -> "当前工具并发已达到上限";
            case TOOL_EXECUTION_TIMEOUT -> "工具执行超时";
            default -> "工具执行失败";
        };
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /** 一个主 Agent Run 的工具调用计数，不跨轮次、不写入 JSONL。 */
    static final class InvocationBudget {
        private final int maximum;
        private final AtomicInteger used = new AtomicInteger();

        InvocationBudget(int maximum) {
            this.maximum = Math.max(0, maximum);
        }

        boolean tryAcquire() {
            while (true) {
                int current = used.get();
                if (current >= maximum) {
                    return false;
                }
                if (used.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }
    }

    /**
     * 一个主 Agent Run 独立的工具结果 token 预算。reserve 只允许至少容纳一个最小结果的
     * 调用进入 handler；实际结果返回后再按送入模型的序列化文本结算。
     */
    static final class ToolResultBudget {
        private final long maximum;
        private long used;

        ToolResultBudget(long maximum) {
            if (maximum < MIN_TOOL_RESULT_TOKENS) {
                throw new IllegalArgumentException("工具结果预算不足以容纳最小结构化结果");
            }
            this.maximum = maximum;
        }

        synchronized Reservation reserve() {
            if (maximum - used < MIN_TOOL_RESULT_TOKENS) {
                return null;
            }
            used += MIN_TOOL_RESULT_TOKENS;
            return new Reservation(MIN_TOOL_RESULT_TOKENS);
        }

        synchronized long availableForCurrent(Reservation reservation) {
            if (reservation == null) {
                return maximum - used;
            }
            if (!reservation.active()) {
                return maximum - used;
            }
            return maximum - (used - reservation.minimum());
        }

        synchronized boolean commit(Reservation reservation, long actualTokens) {
            if (reservation == null || actualTokens < 0 || !reservation.active()) {
                return true;
            }
            long prior = used - reservation.minimum();
            if (prior + actualTokens > maximum) {
                used = prior;
                reservation.deactivate();
                return false;
            }
            used = prior + actualTokens;
            reservation.deactivate();
            return true;
        }

        synchronized void cancel(Reservation reservation) {
            if (reservation != null && reservation.active()) {
                used = Math.max(0, used - reservation.minimum());
                reservation.deactivate();
            }
        }

        static final class Reservation {
            private final long minimum;
            private boolean active = true;

            Reservation(long minimum) {
                this.minimum = minimum;
            }

            long minimum() {
                return minimum;
            }

            boolean active() {
                return active;
            }

            void deactivate() {
                active = false;
            }
        }
    }
}
