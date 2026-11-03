package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.Account;

public final class LoginService {
  private final AccountRepository accounts;
  private final PasswordVerifier passwords;
  private final String dummyHash;
  private final LoginBudget budget;

  public LoginService(
      AccountRepository accounts,
      PasswordVerifier passwords,
      String dummyHash,
      LoginBudget budget) {
    this.accounts = accounts;
    this.passwords = passwords;
    this.dummyHash = dummyHash;
    this.budget = budget;
  }

  public Account login(String schoolCode, String username, String password, String source) {
    try (var permit = budget.acquire(schoolCode, username, source)) {
      var account = accounts.findActive(schoolCode, username);
      account.ifPresent(value -> permit.identify(value.id()));
      boolean matches =
          passwords.matches(password, account.map(Account::passwordHash).orElse(dummyHash));
      if (account.isEmpty() || !matches) throw new AuthenticationFailed();
      return account.get();
    }
  }
}
