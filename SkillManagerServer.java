///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//SOURCES src/main/java/dev/skillmanager/shared/dto/SkillSummary.java
//SOURCES src/main/java/dev/skillmanager/shared/dto/SkillVersion.java
//SOURCES src/main/java/dev/skillmanager/shared/dto/ListResponse.java
//SOURCES src/main/java/dev/skillmanager/shared/dto/SearchResponse.java
//SOURCES src/main/java/dev/skillmanager/shared/dto/PublishResponse.java
//SOURCES src/main/java/dev/skillmanager/shared/dto/Campaign.java
//SOURCES src/main/java/dev/skillmanager/shared/dto/CreateCampaignRequest.java
//SOURCES src/main/java/dev/skillmanager/shared/dto/SponsoredPlacement.java
//SOURCES src/main/java/dev/skillmanager/shared/util/Archives.java
//SOURCES src/main/java/dev/skillmanager/shared/util/Fs.java
//SOURCES src/main/java/dev/skillmanager/shared/util/BundleMetadata.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/SkillRegistryApp.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/SkillRegistryController.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/SkillStorage.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/SkillIndexEntry.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/CampaignStorage.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/AdMatcher.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/AdsController.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/SecurityConfig.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/AuthorizationServerConfig.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/KeyStoreProvider.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/AuthController.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/RegisterController.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/TokenCustomizer.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/UserAccountDetailsService.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/reset/MailService.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/reset/PasswordResetService.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/auth/reset/PasswordResetController.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/PasswordResetToken.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/PasswordResetTokenRepository.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/UserAccount.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/UserAccountRepository.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/ImpressionRow.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/ImpressionRepository.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/ConversionRow.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/ConversionRepository.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/SkillName.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/SkillNameRepository.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/SkillVersionRow.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/persistence/SkillVersionRowRepository.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/publish/Semver.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/publish/PublishException.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/publish/GitHubFetcher.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/publish/SkillPublishService.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/bootstrap/SkillBootstrapper.java
//SOURCES server-java/src/main/java/dev/skillmanager/server/ads/AdStatsController.java
//FILES application.properties=server-java/src/main/resources/application.properties
//JAVA_OPTIONS -Dorg.slf4j.simpleLogger.defaultLogLevel=info
//JAVA_OPTIONS -Dspring.main.banner-mode=off
// Spring Boot starter brings its own logback-classic; don't pull slf4j-simple.
//DEPS org.springframework.boot:spring-boot-starter-web:3.3.4
//DEPS org.springframework.boot:spring-boot-starter-data-jpa:3.3.4
//DEPS org.springframework.boot:spring-boot-starter-security:3.3.4
//DEPS org.springframework.boot:spring-boot-starter-oauth2-resource-server:3.3.4
//DEPS org.springframework.boot:spring-boot-starter-oauth2-authorization-server:3.3.4
//DEPS org.springframework.boot:spring-boot-starter-mail:3.3.4
//DEPS org.postgresql:postgresql:42.7.4
//DEPS org.bouncycastle:bcpkix-jdk18on:1.78.1
//DEPS com.fasterxml.jackson.core:jackson-annotations:2.20
//DEPS com.fasterxml.jackson.core:jackson-databind:2.20.2
//DEPS org.apache.commons:commons-compress:1.27.1

import dev.skillmanager.server.SkillRegistryApp;

public class SkillManagerServer {
    // x-release-please-start-version
    public static final String VERSION = "0.12.1";
    // x-release-please-end

    public static void main(String[] args) {
        if (args.length == 1 && ("--version".equals(args[0]) || "-V".equals(args[0]))) {
            System.out.println("skill-manager-server " + VERSION);
            return;
        }
        SkillRegistryApp.main(args);
    }
}
