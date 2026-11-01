package cn.uusmart.classroom.domain;

public record Account(long id, long tenantId, String username, String passwordHash, Role role) {
  public Actor actor() {
    return new Actor(id, tenantId, role);
  }
}
