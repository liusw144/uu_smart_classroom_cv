package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.Account;
import java.util.Optional;

public interface AccountRepository {
  Optional<Account> findActive(String schoolCode, String username);

  Optional<Account> findActiveById(long id);
}
