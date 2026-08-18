export const FOLLOW_BOTTOM_THRESHOLD_PX = 72

export type ScrollMetrics = {
  scrollHeight: number
  clientHeight: number
  scrollTop: number
}

/** 距离底部的非负像素数；浏览器亚像素与 overscroll 不产生负数。 */
export function distanceFromBottom(metrics: ScrollMetrics): number {
  return Math.max(0, metrics.scrollHeight - metrics.clientHeight - metrics.scrollTop)
}

export function isNearBottom(
  metrics: ScrollMetrics,
  threshold = FOLLOW_BOTTOM_THRESHOLD_PX,
): boolean {
  return distanceFromBottom(metrics) <= threshold
}

/** 用户滚动后的 Follow Mode 转换；程序滚动保持原状态，避免误判为主动阅读。 */
export function followModeAfterScroll(
  current: boolean,
  metrics: ScrollMetrics,
  programmatic: boolean,
): boolean {
  return programmatic ? current : isNearBottom(metrics)
}
