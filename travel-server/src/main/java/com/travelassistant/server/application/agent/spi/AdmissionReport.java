package com.travelassistant.server.application.agent.spi;

import java.time.Instant;
import java.util.List;

/** 准入报告。每次拒绝都必须带原因码，供接入方自助修复。 */
public record AdmissionReport(
    String contractVersion,
    String providerId,
    String status,
    String stage,
    List<RuleFailure> failures,
    List<RuleFailure> warnings,
    Instant checkedAt) {

  public boolean blocked() {
    return !failures.isEmpty();
  }

  public String lastRejectionCode() {
    return failures.isEmpty() ? null : failures.get(0).code();
  }
}
