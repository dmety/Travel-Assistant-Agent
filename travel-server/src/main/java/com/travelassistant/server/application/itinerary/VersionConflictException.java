package com.travelassistant.server.application.itinerary;

/** 乐观锁冲突：客户端携带的版本号与服务端不一致，拒绝覆盖。 */
public class VersionConflictException extends RuntimeException {
  private final long currentVersion;

  public VersionConflictException(long currentVersion) {
    super("版本冲突，已被他人修改，当前版本=" + currentVersion);
    this.currentVersion = currentVersion;
  }

  public long currentVersion() {
    return currentVersion;
  }
}
