package cn.uusmart.classroom.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "cn.uusmart.classroom")
@MapperScan("cn.uusmart.classroom.infrastructure")
public class ClassroomApplication {
  public static void main(String[] args) {
    SpringApplication.run(ClassroomApplication.class, args);
  }
}
