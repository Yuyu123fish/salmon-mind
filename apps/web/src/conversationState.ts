import type { Conversation, ConversationDetail } from './conversationApi.ts'

/**
 * Conversation 快照单调合并：确认序号优先，同序号才比较更新时间。
 * 旧终态因此不能覆盖已经确认的标题、Active Leaf 或压缩索引。
 */
export function mergeConversation(current: Conversation, incoming: Conversation): Conversation {
  if (incoming.lastConfirmedSeq > current.lastConfirmedSeq) return incoming
  if (incoming.lastConfirmedSeq < current.lastConfirmedSeq) return current
  return instantValue(incoming.updatedAt) >= instantValue(current.updatedAt) ? incoming : current
}

/** 打开/刷新响应也服从同一单调规则，避免慢请求覆盖流事件刚确认的详情。 */
export function mergeConversationDetail(
  current: ConversationDetail | undefined,
  incoming: ConversationDetail,
): ConversationDetail {
  if (current === undefined) return incoming
  const conversation = mergeConversation(current.conversation, incoming.conversation)
  if (conversation === current.conversation) {
    return { ...current, conversation }
  }
  return { ...incoming, conversation }
}

function instantValue(value: string): number {
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? Number.NEGATIVE_INFINITY : parsed
}
