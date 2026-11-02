package cn.uusmart.classroom.api.config;

import cn.uusmart.classroom.application.*;
import org.springframework.context.annotation.*;

@Configuration
public class UseCaseConfiguration {
  @Bean
  PasswordHasher passwordHasher(
      org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder) {
    return new PasswordHasher() {
      public String hash(String password) {
        return encoder.encode(password);
      }

      public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
      }
    };
  }

  @Bean
  IdentityManagementService identityManagementService(
      IdentityManagementRepository repository,
      AccountRepository accounts,
      PasswordHasher passwords,
      MediaRepository media) {
    return new IdentityManagementService(repository, accounts, passwords, media);
  }

  @Bean
  MediaService mediaService(
      MediaRepository repository,
      AssetStorage storage,
      TeachingService teaching,
      SchoolContentService catalog,
      VisionGateway vision,
      cn.uusmart.classroom.application.pipeline.QueuedJobService queue) {
    return new MediaService(repository, storage, teaching, catalog, vision, queue);
  }

  @Bean
  SchoolContentService schoolContentService(SchoolContentRepository repository) {
    return new SchoolContentService(repository);
  }

  @Bean
  SchoolOverviewService schoolOverviewService(SchoolOverviewRepository repository) {
    return new SchoolOverviewService(repository);
  }

  @Bean
  TeachingService teachingService(TeachingRepository repository, AccountRepository accounts) {
    return new TeachingService(repository, accounts);
  }
}
