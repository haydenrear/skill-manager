package dev.skillmanager.server.auth.reset;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public password-reset surface:
 *
 * <ul>
 *   <li>{@code POST /auth/password-reset/request} — anonymous; always
 *       202 so a caller can't probe for valid emails.</li>
 *   <li>{@code GET /auth/password-reset/confirm?token=...} — serves a
 *       minimal HTML form the emailed link lands on. If the token is
 *       expired or already used, renders a static "link expired" page.</li>
 *   <li>{@code POST /auth/password-reset/confirm} — consumes the token
 *       (form-encoded {@code token} + {@code password}); redirects to
 *       a success page so a form re-submit can't replay.</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> request(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        service.request(email, stripTrailingSlash(base));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "If that email is registered, a password-reset link has been sent.");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    @GetMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> form(@RequestParam("token") String token) {
        if (service.lookup(token).isEmpty()) return ResponseEntity.ok(expiredPage());
        return ResponseEntity.ok(formPage(token));
    }

    @PostMapping(value = "/confirm",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> submit(@RequestParam("token") String token,
                                         @RequestParam("password") String password) {
        try {
            boolean ok = service.consume(token, password);
            return ResponseEntity.ok(ok ? successPage() : expiredPage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorPage(e.getMessage(), token));
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String formPage(String token) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Reset your password</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:420px;margin:60px auto;padding:0 16px}"
                + "input,button{font:inherit;padding:8px;margin:4px 0;box-sizing:border-box;width:100%}"
                + "button{background:#0a66c2;color:#fff;border:0;padding:10px;cursor:pointer}</style></head>"
                + "<body><h1>Reset your password</h1>"
                + "<form method=\"post\" action=\"/auth/password-reset/confirm\">"
                + "<input type=\"hidden\" name=\"token\" value=\"" + escape(token) + "\">"
                + "<label>New password (min 10 chars)<br>"
                + "<input type=\"password\" name=\"password\" minlength=\"10\" required autofocus></label>"
                + "<button type=\"submit\">Set new password</button></form></body></html>";
    }

    private static String successPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Password updated</title></head>"
                + "<body style=\"font-family:system-ui;margin:60px auto;max-width:420px;padding:0 16px\">"
                + "<h1>Password updated</h1><p>You can close this tab and sign in with your new password.</p>"
                + "</body></html>";
    }

    private static String expiredPage() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Link expired</title></head>"
                + "<body style=\"font-family:system-ui;margin:60px auto;max-width:420px;padding:0 16px\">"
                + "<h1>This link has expired</h1>"
                + "<p>Request a new reset link with <code>skill-manager reset-password --email &lt;your-email&gt;</code>.</p>"
                + "</body></html>";
    }

    private static String errorPage(String message, String token) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Reset failed</title></head>"
                + "<body style=\"font-family:system-ui;margin:60px auto;max-width:420px;padding:0 16px\">"
                + "<h1>Reset failed</h1><p>" + escape(message) + "</p>"
                + "<p><a href=\"/auth/password-reset/confirm?token=" + escape(token) + "\">Try again</a></p>"
                + "</body></html>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
