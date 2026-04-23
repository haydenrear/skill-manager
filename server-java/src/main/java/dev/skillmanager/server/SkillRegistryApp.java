package dev.skillmanager.server;

import dev.skillmanager.server.persistence.ImpressionRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Path;

@SpringBootApplication
public class SkillRegistryApp {

    @Bean
    public Path registryRoot() {
        String env = System.getenv("SKILL_REGISTRY_ROOT");
        return env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".skill-registry");
    }

    @Bean
    public SkillStorage skillStorage(Path registryRoot) throws IOException {
        return new SkillStorage(registryRoot);
    }

    @Bean
    public CampaignStorage campaignStorage(Path registryRoot) throws IOException {
        return new CampaignStorage(registryRoot);
    }

    @Bean
    public AdMatcher adMatcher(CampaignStorage campaigns, SkillStorage skills, ImpressionRepository impressions) {
        return new AdMatcher(campaigns, skills, impressions);
    }

    public static void main(String[] args) {
        SpringApplication.run(SkillRegistryApp.class, args);
    }
}
