package com.travelassistant.server.application.agent.spi;

import java.util.Map;
import java.util.Set;

/**
 * 准入闸门 G1 至 G4 与运行期熔断的统一入口，对应《Agent 接入规范》「与运行时的接口」一节。
 *
 * <p>G0 是纯离线静态校验，由 {@link ContractValidator#validateConfig} 与
 * {@code tools/agent/check_provider_contract.py} 承担，注册表加载时同步执行。
 * G1 至 G4 需要调用 Provider，故做成显式触发，不拖慢启动与 reload。
 *
 * <p>探针方法允许注入 {@link AgentProvider}：生产路径传 {@code null}，控制器按配置构造
 * {@link HttpAgentAdapter} 发真实请求；测试路径注入本地桩，验证同一套判定逻辑。
 */
public interface AdmissionController {

  /** G1 契约探针加 G2 安全探针。BLOCK 失败即终止，不继续后续闸门。 */
  AdmissionReport probe(String providerId, AgentProvider targetOverride);

  /** G3 影子运行：观察 samples 次调用，BLOCK 违规率超过规范源阈值即拒绝。 */
  AdmissionReport shadow(String providerId, int samples, AgentProvider targetOverride);

  /** G4 人工启用：要求 G3 通过且配置里 enabled=true，状态才转 ACTIVE。 */
  AdmissionReport activate(String providerId);

  /** 运行期违规直接熔断（如管理员手工停用）。 */
  AdmissionReport suspend(String providerId, String reasonCode);

  /** 运行期单次违规计数；连续达到 runtimeViolationToSuspend 次转 SUSPENDED。 */
  AdmissionReport recordRuntimeViolation(String providerId, String code);

  /** 运行期一次成功响应，清零连续违规计数。 */
  void recordRuntimeSuccess(String providerId);

  /** 当前状态机阶段，供注册表决定 descriptor 的 status。 */
  String stage(String providerId);

  /** 是否已过 G4，唯一允许参与路由的判定。 */
  boolean active(String providerId);

  /** 配置变更或重新提交时清空该 Provider 的准入进度。 */
  void reset(String providerId);

  /** reload 时清掉已从配置移除的 id，避免删掉再恢复时沿用旧的 ACTIVE 状态。 */
  void resetAbsent(Set<String> presentIds);

  /** 影子观测计数（samples / violations），供管理接口回显。 */
  Map<String, Integer> shadowStats(String providerId);
}
