package cn.uusmart.classroom.api.auth;

import cn.dev33.satoken.stp.StpUtil;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.Actor;
import org.springframework.stereotype.Component;

@Component
public final class CurrentActor {
  private final AccountRepository accounts;

  public CurrentActor(AccountRepository accounts) {
    this.accounts = accounts;
  }

  public Actor get() {
    StpUtil.checkLogin();
    var account =
        accounts.findActiveById(StpUtil.getLoginIdAsLong()).orElseThrow(AuthenticationFailed::new);
    Object tenant = StpUtil.getSession().get("tenant_id");
    if (tenant == null || !Long.valueOf(account.tenantId()).equals(Long.valueOf(tenant.toString())))
      throw new AuthenticationFailed();
    return new Actor(account.id(), account.tenantId(), account.role());
  }
}
