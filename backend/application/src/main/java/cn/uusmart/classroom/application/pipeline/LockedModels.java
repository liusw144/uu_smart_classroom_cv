package cn.uusmart.classroom.application.pipeline;

import java.util.Map;

public interface LockedModels {
  String version();

  Map<String, String> hashes();

  String aiHash();
}
