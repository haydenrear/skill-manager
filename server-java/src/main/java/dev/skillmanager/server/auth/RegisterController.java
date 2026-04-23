package dev.skillmanager.server.auth;

import dev.skillmanager.server.persistence.UserAccount;
import dev.skillmanager.server.persistence.UserAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Anonymous self-serve signup. Mirrors {@code /oauth2/register} conceptually
 * but for end users, not OAuth2 clients.
 *
 * <p>Rules:
 * <ul>
 *   <li>username: 3-64 chars, {@code [a-z0-9-]}, must start with a letter</li>
 *   <li>email: RFC-5322 lite check (one {@code @}, one dot in the domain)</li>
 *   <li>password: &ge; 10 chars — BCrypt'd before persistence, plaintext
 *       never leaves this method</li>
 *   <li>username collision returns 409; anything else bad is 400</li>
 * </ul>
 *
 * <p>If a row already exists for the username *without* a password hash
 * (a machine account minted by {@code client_credentials} issuance), the
 * endpoint refuses with 409 rather than silently upgrading it to a login
 * account — tokens previously attributed to the subject would suddenly
 * be shared with a human identity.
 */
@RestController
@RequestMapping("/auth")
public class RegisterController {

    private static final Pattern USERNAME = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;

    public RegisterController(UserAccountRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = trimOrNull(body.get("username"));
        String email = trimOrNull(body.get("email"));
        String password = body.get("password");
        String displayName = trimOrNull(body.get("display_name"));

        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "username must be 3-64 chars, lowercase, alphanumeric + dashes, starting with a letter");
        }
        if (email == null || !EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required and must be well-formed");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (users.existsById(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username is taken");
        }

        UserAccount saved = users.save(new UserAccount(
                username,
                displayName == null ? username : displayName,
                email,
                encoder.encode(password)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", saved.getUsername());
        out.put("display_name", saved.getDisplayName());
        out.put("email", saved.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
