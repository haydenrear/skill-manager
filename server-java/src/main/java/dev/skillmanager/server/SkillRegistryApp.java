package dev.skillmanager.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Path;

@SpringBootApplication
public class SkillRegistryApp {

    @Bean
    public SkillStorage skillStorage() throws IOException {
        String env = System.getenv("SKILL_REGISTRY_ROOT");
        Path root = env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".skill-registry");
        return new SkillStorage(root);
    }

    public static void main(String[] args) {
        SpringApplication.run(SkillRegistryApp.class, args);
    }
}
