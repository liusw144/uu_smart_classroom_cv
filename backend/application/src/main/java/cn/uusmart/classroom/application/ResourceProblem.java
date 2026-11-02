package cn.uusmart.classroom.application;

public final class ResourceProblem extends RuntimeException {
  public enum Kind {
    NOT_FOUND,
    CONFLICT,
    INVALID
  }

  private final Kind kind;

  private ResourceProblem(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static ResourceProblem missing() {
    return new ResourceProblem(Kind.NOT_FOUND, "资源不存在或不可见");
  }

  public static ResourceProblem conflict(String message) {
    return new ResourceProblem(Kind.CONFLICT, message);
  }

  public static ResourceProblem invalid(String message) {
    return new ResourceProblem(Kind.INVALID, message);
  }
}
