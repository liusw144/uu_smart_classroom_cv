package cn.uusmart.classroom.application;

public interface PasswordHasher {
  String hash(String password);

  boolean matches(String password, String hash);
}
