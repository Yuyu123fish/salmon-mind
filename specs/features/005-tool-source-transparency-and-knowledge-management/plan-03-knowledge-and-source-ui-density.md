# Feature 005 Stage 03 Plan：Knowledge 与来源区信息密度收口

Status: Draft

对应规格：[spec.md](./spec.md)

前序计划：[plan-01-tool-display-detail-and-source-disclosure.md](./plan-01-tool-display-detail-and-source-disclosure.md)、[plan-02-document-deletion-and-knowledge-ui.md](./plan-02-document-deletion-and-knowledge-ui.md)

实施基线：`codex/feature-005-tool-source-transparency` / `3c05892`

> 本 Plan 只定义 Stage 03 的前端收口顺序、交互合同和验证边界。确认 Plan 只会把状态改为 `Planned`，不代表授权实施、提交或推送。

关于 Knowledge 区域顺序与 Source Disclosure 展开方式，以本轮同步后的 Spec 和本文为最新合同；前序 Plan 保留为各自 Stage 的历史实施依据，其他后端与持久化边界继续有效。

## 1. Stage 目标

Stage 03 处理 Stage 01、Stage 02 真实使用后的四项界面反馈，不新增后端能力：

1. Knowledge 页面在资料概览后先显示默认折叠的检索诊断，再显示资料清单与资料详情。
2. 切片预览由列表内部承担纵向滚动；展开长切片不再持续拉长 Knowledge 外层页面。
3. Assistant 回答底部改为“紧凑召回清单 + 单一活动详情”，在更小高度内展示更完整的来源身份和召回链路。
4. 删除两段指定的说明性文案，只保留简洁、可访问的页面标题和上传入口。

本 Stage 是信息层级与滚动所有权的收口。工具调用、Retrieved Source Payload、Citation 身份、RAG 算法、上下文预算、文档删除和持久化合同全部保持不变。

## 2. 当前基线与根因

- 当前基线提交 `3c05892` 已实现单文档删除、切片分页与逐片展开，并报告服务端 146/146、Web 34/34、lint 和 build 通过；人工浏览器验收仍待执行。Stage 03 修改前端后只补受影响的 Web 验证，不重复无关服务端测试。
- `KnowledgeView` 当前顺序是“标题/上传 → 概览 → 资料清单与详情 → 检索诊断”，与本轮确认的新顺序相反。
- 切片正文虽然默认限高，但展开态取消了单片限高，`.evidence-list` 又没有高度边界；多片展开会把滚动压力转移给 `.knowledge-main`。
- `AssistantEvidenceView` 已默认折叠来源区并区分引用/未引用来源，但每个 `SourceCard` 可以同时展开。来源多、摘录长时，回答气泡会随所有卡片累计增长。
- 现有前端已经能从 `RetrievedSourcePayload` 与对应 `RunTraceItem` 取得文档/网页身份、首次 Tool Call、Result Position、Provider Rank、安全 Query Summary、检索时间、Citation Note 和 Source Excerpt。问题是信息编排，不是后端字段缺失。
- Knowledge 顶部仍展示“把资料放在手边，等它变得可读。”和“页面会持续显示处理进度，只有完整建好索引后才标记为已就绪。”；这两段文字占据首屏，但没有新增操作信息。

实施前重新检查分支、HEAD、工作区和本文状态。若基线已变化，先确认上述根因是否仍成立，不按过期 DOM 结构机械修改。

## 3. 范围与禁止项

### 3.1 本 Stage 包含

- `KnowledgeView` 的区域排序、标题精简和切片内部纵向滚动。
- `AssistantEvidenceView` 的紧凑来源行、单一活动详情、来源内部纵向滚动与 Citation 定位。
- 既有配色、字体和消息/Knowledge 布局下的响应式、键盘、触控和长内容适配。
- `KnowledgeView.test.tsx`、`App.followMode.test.tsx` 等现有测试 seam 的聚焦补充。

### 3.2 本 Stage 明确不包含

- 修改 Server、SSE、Conversation JSONL、Retrieved Source/Citation 类型、Knowledge API 或数据库。
- 增加来源全文、完整 Tool Result、内部检索 Score、可信度、更多摘录长度或新的召回历史。
- 修改 BM25、Vector、RRF、Rerank、Top K、Provider、上下文/输出预算或 Citation 生成规则。
- 修改上传、删除、重试、检索诊断 Pipeline、Markdown 安全策略或 Follow Mode 产品语义。
- 引入虚拟列表、组件库、全局状态、第三个滚动框架或整页视觉重做。

## 4. 固定交互合同

### 4.1 Knowledge 层级与文案

- 页面顺序固定为：简洁标题与上传入口 → 资料概览 → 默认折叠的检索诊断 → 资料清单与当前详情。
- 删除两段指定文案，不用另一句口号或说明段落替换。“本地资料台”从装饰性 kicker 调整为页面 `h1`，继续为 `knowledge-view` 提供可访问名称。
- 上传格式、大小限制、错误提示、概览统计和所有资料管理行为保持原样。
- 检索诊断只改变 DOM 位置，仍默认折叠；输入、请求失效、各阶段结果和错误状态不重建、不复制。

### 4.2 切片预览的滚动所有权

- `evidence-list` 变为有可访问名称、可键盘聚焦的内部纵向滚动区。使用 CSS 的视口相关最大高度，不用 JavaScript 测量固定屏幕高度。
- “切片预览”标题、数量、加载/空/错误状态和分页控件留在滚动区外；滚动区只包裹实际切片卡片。
- 卡片继续默认限高并支持独立展开。展开态可以展示完整正文，但新增高度由切片列表内部吸收，不再无限增加 Knowledge 外层高度；不再给每张卡片增加第二个纵向滚动条。
- 切换文档或页码时沿用现有展开状态重置，并把切片内部滚动位置归零。请求失败或空结果不留下一个无内容的固定高框。
- 内部区域使用稳定 scrollbar gutter、纵向 overscroll containment 和清晰的 `focus-visible`；鼠标滚轮、触控、PageUp/PageDown 与方向键均不能被自定义事件拦截。
- Markdown、纯文本、长表格、代码、URL 和无空格文本的既有安全与横向溢出边界不变。

### 4.3 回答底部的紧凑召回清单

- 来源核验整体继续默认折叠，折叠按钮继续显示“回答已引用 / 本轮召回未引用”数量。
- 展开后，“回答已引用”默认可见；“本轮召回未引用”继续作为二级折叠区。二者连同当前来源详情共用一个有界的内部纵向滚动区，避免回答气泡随来源总量无限增长。
- 每个紧凑来源行优先展示可扫描信息：`L/W` 引用号、文档名或网页标题、本地 Location 或网页站点/Provider、Result Position，以及存在时的 Provider Rank。
- 来源详情从“多个卡片各自展开”改为“全区域唯一活动来源”。打开第二个来源时，第一个来源详情自动关闭；折叠来源总区后不要求清空当前选择。
- 活动详情只组合现有安全字段：
  - 来源身份：本地文档与 Location，或网页标题、站点、Provider、安全 URL 和日期；
  - 召回链路：首次工具序号与工具名、该 Tool Trace 已持久化的安全 Query Summary、Result Position、可选 Provider Rank 和检索时间；
  - 核验内容：已引用来源的 Citation Note，以及该 Retrieved Source 的类型化 Source Excerpt。
- 旧历史缺少 Tool Call、Query Summary、Result Position、Provider Rank 或 Source Excerpt 时直接省略对应项，不显示伪造的“未知”，也不因此阻止其他信息显示。
- Query Summary 只能按 `originToolCallId` 关联同一 Assistant Trace 中的白名单展示字段；不得从 `safeSummary` 猜查询、解析正文或请求新的后端数据。
- 网页链接继续经过现有 `safeHttpUrl` 校验；无效协议、带凭据 URL 和缺失 URL 不生成可点击链接。

### 4.4 Citation 聚焦与响应式

- 点击正文合法 Citation 后，依次展开来源总区、目标分组并把目标设为唯一活动来源，然后在来源内部滚动区聚焦对应行/详情。
- 定位只把目标滚入最近的内部可视区域，不强制整页或消息区滚到底；现有 Follow Mode 的暂停/恢复语义保持不变。
- 桌面与窄屏沿用当前色板和字体，不新增装饰性面板。来源清单是本 Stage 唯一强调的信息结构：窄屏允许标题与元数据换行，但操作不能依赖 hover，也不能产生页面级横向滚动。

## 5. 有序实施步骤

| ID | 端到端结果 | 前置 | 完成后的停点 |
| --- | --- | --- | --- |
| S3-01 | Knowledge 标题、区域顺序与语义收口 | 无 | 新顺序成立，既有诊断功能尚未改动 |
| S3-02 | 切片列表拥有内部滚动 | S3-01 | 长切片不再拉长外层页面 |
| S3-03 | 来源区变为紧凑清单与单一活动详情 | S3-02 | 更多召回信息可按需核验，消息高度有界 |
| S3-04 | Web 回归与人工浏览器验收 | S3-03 | 停止，等待开发者验收 |

### S3-01：Knowledge 顺序与标题

1. 把现有检索诊断节点移动到概览统计与 `knowledge-grid` 之间，不复制 state、handler 或请求逻辑。
2. 移除两段指定文案，把“本地资料台”调整为唯一可见 `h1`；同步删除只为旧 Hero 文案服务且不再使用的样式。
3. 补行为测试，断言标题、诊断、资料清单和详情的可访问语义及 DOM 顺序；不要用 CSS 像素快照证明顺序。

### S3-02：切片内部滚动

1. 为切片列表增加单一滚动容器和 ref；只在文档/页码真正变化时归零 `scrollTop`，不在用户展开单片时抢滚动位置。
2. 用现有响应式断点设置视口相关最大高度、overscroll 和 focus 样式；分页、空态与错误态继续位于容器外。
3. 聚焦测试覆盖翻页/切文档后的展开与滚动重置、空结果语义和可访问 region。JSDOM 只验证状态与结构，实际滚动尺寸留给浏览器验收。

### S3-03：来源清单与活动详情

1. 从现有 Trace 构建按 `toolCallId` 索引的安全展示映射，包含工具序号、工具名和可选 Query Summary；不改变 API 类型。
2. 用一个 `activeSourceId` 或等价单值状态替代可同时展开多项的 `expandedSources`；来源行与详情可以等价提取为局部组件，但不新增全局 store。
3. 把来源列表和活动详情放入同一个可访问内部滚动区；保留总折叠、未引用二级折叠、旧 Payload 兼容和安全链接行为。
4. 更新 Citation 激活逻辑，使目标来源成为唯一活动项并在内部区域聚焦；同时验证 `onLayoutChange` 不破坏 Follow Mode。
5. 聚焦测试覆盖多来源切换、完整召回链路、缺字段、无效 URL、未引用折叠和 Citation 定位。

### S3-04：验证与交付

实现过程中只运行受影响测试；全部代码完成后再运行一次 Web 全量回归。执行 Agent 必须记录真实命令和结果，不自动提交或推送。

## 6. 验证计划

### 6.1 聚焦自动化

```powershell
npm run test --prefix apps/web -- KnowledgeView.test.tsx App.followMode.test.tsx
```

自动化至少证明：

- 两段指定文案不存在，“本地资料台”仍是页面标题；诊断节点位于资料网格之前且默认折叠。
- 切片滚动区具有可访问名称；翻页/切文档会重置展开项和内部位置，而展开卡片不会触发状态误重置。
- 来源总区默认折叠，未引用分组默认折叠；任一时刻只有一个来源详情可见。
- 来源详情正确关联同一 Tool Call 的安全 Query Summary 和召回位置；旧 Payload 缺字段时正常降级。
- 行内 Citation 会选择、聚焦目标来源，并继续遵守现有 Follow Mode 行为。

### 6.2 Stage 级回归

```powershell
npm run test --prefix apps/web
npm run lint --prefix apps/web
npm run build --prefix apps/web
git diff --check
```

本 Stage 不修改 Server，因此不重复基线已经报告的 Maven 全量测试。若实施发现必须修改 Server、API 或 Payload，应停止并回到讨论，而不是自行扩大测试和范围。

### 6.3 人工浏览器验收

至少使用一份多页长文档和一条包含多条本地/网页来源的真实历史或确定性 Fixture，覆盖：

1. 桌面和窄屏下，检索诊断位于资料清单/详情上方、默认折叠，展开后查询与四阶段结果可用。
2. 切片列表出现内部滚动条；展开多个长切片时 Knowledge 外层页面不再按全文累计增长，分页控件始终在滚动区外可操作。
3. 切换页码或文档后，切片列表回到顶部且旧展开状态消失；滚轮、触控和键盘均能进入与离开内部区域。
4. 多来源回答只显示紧凑行；切换来源时始终只有一个详情，召回查询、工具、结果位置、时间、Citation Note 和摘录按现有数据展示。
5. 点击回答中的 `L/W` Citation 后目标来源在内部区域可见并获得焦点；消息区不会被强制滚到底，手动上翻时 Follow Mode 不被重新开启。
6. 长标题、长 URL、长 Query Summary 和长摘录不产生页面级横向滚动；无效 URL 不可点击。

人工浏览器验收未执行时必须明确报告，不能用 JSDOM、lint 或 build 替代。

## 7. 兼容、配置与迁移

- 无数据库、JSONL、HTTP、SSE、索引或对象存储迁移。
- 无新增依赖、环境变量、端口或开发者配置；只需按现有 Web 开发方式重新构建/加载前端。
- 旧 Conversation 缺少 Stage 01 新字段时继续展示来源身份和已有摘录；新布局不得要求历史回填。
- Stage 02 的删除状态、旧响应失效、分页、Markdown 安全渲染和 Knowledge mutation generation 保持原样。

## 8. 风险与停止条件

出现以下任一情况时停止实施并回到讨论：

- “召回信息更详细”必须新增后端字段、延长 Source Excerpt、暴露完整 Tool Result 或内部检索 Score 才能满足。
- 内部滚动只能通过固定屏幕像素、全局 wheel 拦截、不可键盘访问的自定义滑块或新第三方组件实现。
- Citation 聚焦需要改变 Follow Mode、Conversation Active Path 或消息列表滚动权威。
- 调整区域顺序会迫使检索诊断重新请求、复制状态，或破坏删除后的 mutation generation 防旧响应机制。
- 范围扩展到整页视觉重做、来源侧栏/弹窗、虚拟化、移动端专用页面或新的 Knowledge 能力。

可恢复检查点是 S3-01、S3-02、S3-03；任一检查点都应保持应用可构建、已有能力可使用。不要为流程创建临时 Superpowers 文档或额外 worktree。

## 9. 实施报告要求

执行 Agent 必须报告：

- Knowledge 新区域顺序、标题语义、切片滚动所有权和来源单一活动详情的实际实现；
- `Citation → Source Disclosure → active source → internal focus` 的审查结果，以及 Follow Mode 风险点；
- 哪些来源详情来自现有 Retrieved Source，哪些通过 `originToolCallId` 关联现有 Trace，缺字段如何降级；
- 所有自动化命令及通过/失败/跳过结果，人工桌面/窄屏验收是否实际执行；
- 新增配置、依赖、迁移和 Server 改动均应为“无”；若不是，必须说明停止原因；
- 当前 `git status`、提交和推送状态。完成后停止，不自动提交、推送、创建 PR 或把 Feature 标记为 Accepted。

## 10. Plan 确认

当前状态为 `Draft`。开发者确认后只把本文状态改为 `Planned`；实施、提交、推送和 Feature 验收仍需分别授权。
