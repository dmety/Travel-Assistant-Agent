package com.travelassistant.server.application.decision;

/** 候选项不存在（或不属于该行程），映射为 404。 */
public class CandidateNotFoundException extends RuntimeException {
  private final String candidateId;

  public CandidateNotFoundException(String candidateId) {
    super("候选项不存在");
    this.candidateId = candidateId;
  }

  public String candidateId() {
    return candidateId;
  }
}
