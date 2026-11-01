package cn.uusmart.classroom.api.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.uusmart.classroom.application.AuthenticationFailed;
import cn.uusmart.classroom.domain.AccessDenied;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ExceptionAdvice {
  @ExceptionHandler({NotLoginException.class, AuthenticationFailed.class})
  ResponseEntity<?> unauthorized(Exception error) {
    return response(401, "UNAUTHORIZED", "身份无效或已过期");
  }

  @ExceptionHandler({NotPermissionException.class, NotRoleException.class, AccessDenied.class})
  ResponseEntity<?> forbidden(Exception error) {
    return response(403, "FORBIDDEN", "没有执行该操作的权限");
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  ResponseEntity<?> invalid(Exception error) {
    return response(400, "INVALID_REQUEST", "请求字段或格式无效");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception error) {
    LoggerFactory.getLogger(getClass()).error("请求处理失败，异常类型 {}", error.getClass().getName());
    return response(500, "INTERNAL_ERROR", "服务暂时不可用，请提供请求编号排查");
  }

  private ResponseEntity<?> response(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "error",
                Map.of(
                    "code",
                    code,
                    "message",
                    message,
                    "request_id",
                    String.valueOf(MDC.get("request_id")))));
  }
}
