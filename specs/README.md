# Feature 文档约定

`specs/` 保存中大型 Feature 最终确认的产品与实施方案。小需求可以在对话中明确后快速实施，不强制创建文档。

## 目录

```text
specs/
  features/
    NNN-feature-name/
      spec.md
      plan.md
      plan-01-stage-name.md
      report.md
```

- `NNN` 是从 `001` 开始的三位序号，目录名使用稳定的英文短名。
- 中型 Feature 使用 `spec.md` 和一个 `plan.md`。
- 大型 Feature 使用 `spec.md` 和多个 `plan-NN-stage-name.md`，所有 Stage 沿用同一个 Feature 分支。
- 中大型 Feature 在形成可验收增量后维护一个 `report.md`，记录当前已经成立的功能事实。
- 只创建实际需要的文件；尚未拆 Stage 时不要预建空 Plan。

## 状态

文档顶部使用单一状态：

```text
Status: Draft | Specified | Planned | Implementing | Implemented | Accepted
```

- 新建 Spec 和 Plan 默认是 `Draft`。
- 只有开发者确认相应文档后，才能进入 `Specified` 或 `Planned`。
- `Planned` 不代表允许实施，实施仍需要单独明确授权。
- `Implemented` 表示已完成实现与验证、等待验收；`Accepted` 只由开发者确认。

## Spec 内容

Spec 至少包含：

1. Problem Statement
2. Solution
3. Domain Terms
4. User Stories
5. Behavior and Failure Semantics
6. Implementation Decisions
7. Testing Decisions
8. Out of Scope
9. Acceptance Criteria
10. Further Notes

Spec 记录稳定合同，不列容易过时的具体文件路径，不写实施过程流水账。

## Plan 内容

Plan 至少包含：

1. 当前基线与前置条件
2. 实施范围和禁止范围
3. 有序实施步骤
4. 数据迁移与兼容方式
5. 验证命令和真实验收方式
6. 风险、停止条件与恢复点
7. 实施报告要求

Plan 可以保留低风险实现细节的调整空间，但不能让执行 Agent 自行改变产品语义、数据权威、公开接口或 Feature 范围。

## Report 内容

Report 面向希望理解 Feature 能力和整体处理过程的开发者，不是实施路线、代码变更清单或代码审查报告。至少说明：

1. 新增了哪些用户可感知或维护者需要理解的能力。
2. 数据或请求从入口到结果的实际处理流程。
3. 实现过程中真正困难的边界、取舍和解决方式。
4. 最终形成的效果、可靠性语义和仍然存在的限制。
5. 验收证据及其边界；没有执行的真实外部验证不能写成已经通过。

Report 避免罗列类名、方法名、逐文件 Diff、测试用例清单和按时间排列的实施步骤。大型 Feature 可以在后续 Stage 完成后持续更新同一份报告，但只能把已经实现或验收的能力写成当前事实；尚未成立的部分必须明确标注边界。

## 文档边界

- `specs/` 可以描述尚未实现但已经确认的目标，必须用状态清楚标识。
- `README.md` 和稳定 `docs/` 只描述当前已经验收的事实。
- 实施提示词、检查点、测试日志和临时分析不在 `specs/` 长期保存。
- 有长期解释价值且难以逆转的架构取舍，后续可以单独形成 ADR；不要为普通实现决定创建 ADR。
