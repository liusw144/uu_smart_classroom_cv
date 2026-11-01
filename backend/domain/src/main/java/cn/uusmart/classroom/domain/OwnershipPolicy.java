package cn.uusmart.classroom.domain;

public final class OwnershipPolicy {
  private OwnershipPolicy() {}

  public static void requireAccess(Actor actor, long tenantId, long ownerId) {
    if (actor.tenantId() != tenantId
        || actor.role() == Role.PLATFORM_OPERATOR
        || (actor.role() == Role.TEACHER && actor.userId() != ownerId)) {
      throw new AccessDenied();
    }
  }
}
