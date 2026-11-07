package com.arina.scheduler;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final JwtEncoder tokens;

    AuthController(AccountRepository accounts, PasswordEncoder passwords, JwtEncoder tokens) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    record Registration(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(min = 8, max = 72) String password,
                        @NotBlank @Pattern(regexp = "CANDIDATE|RECRUITER") String role) {}
    record Login(@NotBlank @Email @Size(max = 254) String email,
                 @NotBlank @Size(max = 72) String password) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@Valid @RequestBody Registration request) {
        validatePasswordBytes(request.password());
        try {
            var account = new Account(request.email().toLowerCase(Locale.ROOT),
                    passwords.encode(request.password()), request.role());
            account = accounts.saveAndFlush(account);
            return Map.of("id", account.id, "email", account.email, "role", account.role);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
    }

    @PostMapping("/login")
    Map<String, String> login(@Valid @RequestBody Login request) {
        validatePasswordBytes(request.password());
        Account account = accounts.findByEmail(request.email().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwords.matches(request.password(), account.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(account.id.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).claim("role", account.role).build();
        String token = tokens.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return Map.of("token", token);
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        }
    }
}
