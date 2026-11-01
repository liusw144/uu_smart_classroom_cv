package cn.uusmart.classroom.domain;

public final class AccessDenied extends RuntimeException {
  public AccessDenied() {
    super("没有访问该资源的权限");
  }
}
