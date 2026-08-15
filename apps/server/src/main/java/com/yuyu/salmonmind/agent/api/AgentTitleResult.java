package com.yuyu.salmonmind.agent.api;

/**
 * 首次标题生成结果。title 为 null 表示模型返回空白或输出被截断，调用方应保留默认标题；
 * 标题规范化与合法性判定属于 conversation 侧规则。provider/model 用于写入 Title Entry
 * 的模型元数据，不构成成功判据。
 */
public record AgentTitleResult(String title, String provider, String model) {
}
