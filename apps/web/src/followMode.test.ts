import { describe, expect, it } from 'vitest'
import {
  FOLLOW_BOTTOM_THRESHOLD_PX,
  distanceFromBottom,
  followModeAfterScroll,
  isNearBottom,
} from './followMode.ts'

describe('follow mode boundary', () => {
  it('keeps following at the threshold and exits immediately beyond it', () => {
    const base = { scrollHeight: 1_000, clientHeight: 300 }
    expect(isNearBottom({ ...base, scrollTop: 700 - FOLLOW_BOTTOM_THRESHOLD_PX })).toBe(true)
    expect(isNearBottom({ ...base, scrollTop: 699 - FOLLOW_BOTTOM_THRESHOLD_PX })).toBe(false)
  })

  it('clamps browser overscroll to zero distance', () => {
    expect(distanceFromBottom({ scrollHeight: 500, clientHeight: 300, scrollTop: 250 })).toBe(0)
  })

  it('exits on user upward reading, restores at bottom, and ignores programmatic movement', () => {
    const away = { scrollHeight: 1_000, clientHeight: 300, scrollTop: 200 }
    const bottom = { scrollHeight: 1_000, clientHeight: 300, scrollTop: 700 }

    expect(followModeAfterScroll(true, away, false)).toBe(false)
    expect(followModeAfterScroll(false, bottom, false)).toBe(true)
    expect(followModeAfterScroll(true, away, true)).toBe(true)
  })
})
