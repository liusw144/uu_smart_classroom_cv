package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.*;
import java.util.Optional;

public interface IdentityManagementRepository {
  Page<SchoolUser> users(Actor actor, String query, int page, int size);

  Optional<SchoolUser> user(Actor actor, long id);

  long create(Actor actor, String username, String hash, String displayName, Role role);

  void update(Actor actor, long id, String displayName, Role role, boolean enabled);

  void delete(Actor actor, long id);

  void profile(Actor actor, String displayName, String email, String avatarAssetId);

  void password(Actor actor, String previousHash, String newHash);
}
