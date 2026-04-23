package dev.skillmanager.server.auth;

import dev.skillmanager.server.persistence.UserAccount;
import dev.skillmanager.server.persistence.UserAccountRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Adapter from {@link UserAccount} rows to Spring Security's
 * {@link UserDetails}. The form-login filter chain consults this during
 * {@code POST /login}; the authorization server's {@code /oauth2/authorize}
 * endpoint delegates to the same authentication manager so browser-driven
 * CLI logins resolve the user through here too.
 *
 * <p>Rows without a {@code password_hash} (machine accounts minted by
 * {@code client_credentials} token issuance) are not login-capable; we
 * surface that as {@link UsernameNotFoundException} to avoid leaking the
 * distinction to a login form user.
 */
@Service
public class UserAccountDetailsService implements UserDetailsService {

    private final UserAccountRepository users;

    public UserAccountDetailsService(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = users.findById(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        if (account.getPasswordHash() == null || account.getPasswordHash().isBlank()) {
            throw new UsernameNotFoundException(username);
        }
        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .authorities("ROLE_USER")
                .build();
    }
}
