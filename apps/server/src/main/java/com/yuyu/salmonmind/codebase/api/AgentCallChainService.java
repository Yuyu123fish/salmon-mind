package com.yuyu.salmonmind.codebase.api;

/** Agent 侧的调用链 prepare/confirm 小接口；不暴露数据根或 JSONL 布局。 */
public interface AgentCallChainService {

    /** 在成功回答前准备不可见 pending 链；源码和 HEAD 会在此处重新复核。 */
    CallChainReference prepare(CallChainPrepareRequest request);

    /** Assistant JSONL 成功追加后幂等确认 pending，使调用链正式可见。 */
    CallChainReference confirm(CallChainConfirmation confirmation);
}
