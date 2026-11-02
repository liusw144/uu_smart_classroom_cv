package cn.uusmart.classroom.api.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.uusmart.classroom.api.auth.CurrentActor;
import cn.uusmart.classroom.application.*;
import cn.uusmart.classroom.domain.Actor;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuditInterceptor implements HandlerInterceptor {
  private static final String ACTOR = AuditInterceptor.class.getName();
  private final CurrentActor current;
  private final SchoolOverviewService overview;

  public AuditInterceptor(CurrentActor current, SchoolOverviewService overview) {
    this.current = current;
    this.overview = overview;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    try {
      request.setAttribute(ACTOR, current.get());
    } catch (NotLoginException | AuthenticationFailed ignored) {
    }
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
    if (request.getAttribute(ACTOR) instanceof Actor actor
        && !request.getRequestURI().equals("/api/v1/logs")) {
      try {
        overview.append(
            actor,
            request.getMethod(),
            request.getRequestURI(),
            response.getStatus(),
            String.valueOf(MDC.get("request_id")));
      } catch (RuntimeException failure) {
        LoggerFactory.getLogger(getClass()).error("请求审计保存失败，异常类型 {}", failure.getClass().getName());
      }
    }
  }
}
