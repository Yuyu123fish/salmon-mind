import { describe, expect, it } from 'vitest'
import { mergeConversation } from '../conversationState.ts'
import type { Conversation } from '../conversationApi.ts'

describe('conversation snapshot merge', () => {
  it('keeps a newer confirmed title when an older run terminal arrives later', () => {
    const titled = conversation(3, '模型生成的标题', '2026-08-18T01:00:00Z')
    const staleTerminal = conversation(2, '新对话', '2026-08-18T02:00:00Z')

    expect(mergeConversation(titled, staleTerminal)).toBe(titled)
  })

  it('uses updatedAt only when the confirmation sequence is equal', () => {
    const earlier = conversation(3, '旧标题', '2026-08-18T01:00:00Z')
    const later = conversation(3, '新标题', '2026-08-18T01:00:01Z')

    expect(mergeConversation(earlier, later)).toBe(later)
    expect(mergeConversation(later, earlier)).toBe(later)
  })
})

function conversation(lastConfirmedSeq: number, title: string, updatedAt: string): Conversation {
  return {
    id: 'conversation-1',
    workspaceId: 'workspace-1',
    title,
    historyFormatVersion: 1,
    activeLeafEntryId: 'assistant-1',
    lastConfirmedSeq,
    latestCompactionEntryId: null,
    latestCompactionSeq: null,
    latestCompactionByteOffset: null,
    createdAt: '2026-08-18T00:00:00Z',
    updatedAt,
  }
}
