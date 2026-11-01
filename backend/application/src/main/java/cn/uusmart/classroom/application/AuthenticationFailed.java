package cn.uusmart.classroom.application;

public final class AuthenticationFailed extends RuntimeException {
  public AuthenticationFailed() {
    super("学校、账号或密码错误，或账号不可用");
  }
}
