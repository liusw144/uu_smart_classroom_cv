package cn.uusmart.classroom.application;

public final class ServiceUnavailable extends RuntimeException {
  public ServiceUnavailable(String message) {
    super(message);
  }
}
