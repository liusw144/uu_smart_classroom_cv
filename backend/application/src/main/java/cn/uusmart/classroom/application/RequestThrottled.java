package cn.uusmart.classroom.application;

public final class RequestThrottled extends RuntimeException {
  private final long retryAfter;

  public RequestThrottled(long retryAfter) {
    super("请求过于频繁，请稍后重试");
    this.retryAfter = Math.max(1, retryAfter);
  }

  public long retryAfter() {
    return retryAfter;
  }
}
