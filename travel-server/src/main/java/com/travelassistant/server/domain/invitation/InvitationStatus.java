package com.travelassistant.server.domain.invitation;

/**
 * 邀请状态。
 *
 * <p>只有 {@link #PENDING} 可以被接受或拒绝；一旦离开该状态即不可再变更，
 * 再次处理会返回冲突（409），而不是静默覆盖结果。
 */
public enum InvitationStatus {
  /** 已发出，等待受邀者处理。 */
  PENDING,
  /** 受邀者已接受，已成为行程成员。 */
  ACCEPTED,
  /** 受邀者已拒绝。 */
  REJECTED,
  /** 创建者撤回，或行程被删除导致邀请失效。 */
  CANCELLED
}
