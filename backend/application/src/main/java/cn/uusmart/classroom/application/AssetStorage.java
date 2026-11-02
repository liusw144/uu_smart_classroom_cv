package cn.uusmart.classroom.application;

public interface AssetStorage {
  void write(long tenantId, String id, byte[] content);

  byte[] read(long tenantId, String id);

  void remove(long tenantId, String id);
}
