package cn.uusmart.classroom.application;

public interface PasswordVerifier {
  boolean matches(String raw, String encoded);
}
