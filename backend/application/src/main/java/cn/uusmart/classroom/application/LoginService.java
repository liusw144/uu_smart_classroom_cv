package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.Account;

public final class LoginService {
  private final AccountRepository accounts;
  private final PasswordVerifier passwords;
  private final String dummyHash;

  public LoginService(AccountRepository accounts, PasswordVerifier passwords, String dummyHash) {
    this.accounts = accounts;
    this.passwords = passwords;
    this.dummyHash = dummyHash;
  }

  public Account login(String schoolCode, String username, String password) {
    var account = accounts.findActive(schoolCode, username);
    boolean matches =
        passwords.matches(password, account.map(Account::passwordHash).orElse(dummyHash));
    if (account.isEmpty() || !matches) throw new AuthenticationFailed();
    return account.get();
  }
}
