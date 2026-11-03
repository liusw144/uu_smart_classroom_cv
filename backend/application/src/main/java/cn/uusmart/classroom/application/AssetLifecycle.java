package cn.uusmart.classroom.application;

import cn.uusmart.classroom.domain.Actor;
import cn.uusmart.classroom.domain.MediaAsset;

public interface AssetLifecycle {
  void create(MediaAsset asset, byte[] content);

  void delete(Actor actor, String id);
}
