package cn.uusmart.classroom.application;

public final class StorageLimit extends RuntimeException {
  private final boolean capacity;

  public StorageLimit(boolean capacity) {
    super(capacity ? "存储容量暂不足，请联系管理员" : "素材配额不足，请清理素材或联系管理员");
    this.capacity = capacity;
  }

  public boolean capacity() {
    return capacity;
  }
}
