package cn.uusmart.classroom.infrastructure;

import cn.uusmart.classroom.application.AccountRepository;
import cn.uusmart.classroom.domain.Account;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAccountRepository implements AccountRepository {
  private final AccountMapper mapper;

  public MybatisAccountRepository(AccountMapper mapper) {
    this.mapper = mapper;
  }

  public Optional<Account> findActive(String schoolCode, String username) {
    return Optional.ofNullable(mapper.findActive(schoolCode, username));
  }

  public Optional<Account> findActiveById(long id) {
    return Optional.ofNullable(mapper.findActiveById(id));
  }
}
