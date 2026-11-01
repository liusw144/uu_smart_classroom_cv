package cn.uusmart.classroom.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OwnershipPolicyTest {
  @Test
  void teacherCanAccessOwnResource() {
    assertDoesNotThrow(() -> OwnershipPolicy.requireAccess(new Actor(7, 1, Role.TEACHER), 1, 7));
  }

  @Test
  void teacherCannotAccessOtherTeacher() {
    assertThrows(
        AccessDenied.class,
        () -> OwnershipPolicy.requireAccess(new Actor(7, 1, Role.TEACHER), 1, 8));
  }

  @Test
  void schoolAdminCannotCrossTenant() {
    assertThrows(
        AccessDenied.class,
        () -> OwnershipPolicy.requireAccess(new Actor(7, 1, Role.SCHOOL_ADMIN), 2, 7));
  }

  @Test
  void platformOperatorHasNoImplicitClassroomAccess() {
    assertThrows(
        AccessDenied.class,
        () -> OwnershipPolicy.requireAccess(new Actor(7, 1, Role.PLATFORM_OPERATOR), 1, 8));
  }

  @Test
  void schoolAdminCanAccessOwnSchool() {
    assertDoesNotThrow(
        () -> OwnershipPolicy.requireAccess(new Actor(7, 1, Role.SCHOOL_ADMIN), 1, 8));
  }
}
