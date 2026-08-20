package com.yuyu.salmonmind.agent.infrastructure.reactagent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;

/** 在每次真正调用 Chat Model 前执行 RunContextMeter。 */
final class RunContextMeterInterceptor extends ModelInterceptor {

    @Override
    public String getName() {
        return "run-context-meter";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Object value = request.getContext().get(RunContextMeter.METADATA_KEY);
        if (!(value instanceof RunContextMeter meter)) {
            return handler.call(request);
        }
        RunContextMeter.Prepared prepared = meter.prepare(request);
        return handler.call(prepared.request());
    }
}
