package dev.skillmanager.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {
    List<PasswordResetToken> findByUsername(String username);
}
