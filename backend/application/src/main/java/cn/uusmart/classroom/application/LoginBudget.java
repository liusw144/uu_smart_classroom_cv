package cn.uusmart.classroom.application;

public interface LoginBudget {
  Permit acquire(String school, String username, String source);

  interface Permit extends AutoCloseable {
    void identify(long accountId);

    @Override
    void close();
  }
}
