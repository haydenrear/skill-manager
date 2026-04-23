package dev.skillmanager.server.auth;

import dev.skillmanager.server.persistence.UserAccount;
import dev.skillmanager.server.persistence.UserAccountRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identity echo for the bearer presented to the registry.
 *
 * <p>All token issuance goes through the embedded authorization server
 * ({@code /oauth2/token}); this controller only reads the validated JWT
 * and joins it against the {@code users} table.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserAccountRepository users;

    public AuthController(UserAccountRepository users) {
        this.users = users;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        String username = jwt.getSubject();
        UserAccount user = users.findById(username).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", username);
        out.put("display_name", user == null ? username : user.getDisplayName());
        out.put("email", user == null ? null : user.getEmail());
        return out;
    }
}
