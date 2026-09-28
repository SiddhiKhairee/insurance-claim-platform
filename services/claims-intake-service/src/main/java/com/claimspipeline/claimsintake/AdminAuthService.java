package com.claimspipeline.claimsintake;

import com.claimspipeline.claimsintake.LoginAttemptLimiter.Action;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin sign-up and login (PLAN.md §12, 2026-09-28: "how the admin signup code works").
 *
 * <p>Sign-up requires {@code ADMIN_SIGNUP_CODE} from .env; unset or empty means sign-up is
 * disabled, never open. Order of checks in both flows: rate limit first, then the secret (code or
 * password), and only then anything that could reveal whether a username exists.
 */
@Service
public class AdminAuthService {

  static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,32}");
  static final int MIN_PASSWORD_CHARS = 12;
  /** bcrypt only uses the first 72 bytes; longer passwords are rejected rather than truncated. */
  static final int MAX_PASSWORD_BYTES = 72;

  private static final String SIGNUP_REFUSED = "Sign-up not allowed";
  private static final String LOGIN_FAILED = "Invalid username or password";
  private static final String TOO_MANY = "Too many attempts. Try again later.";

  private final AdminUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AdminTokenService tokens;
  private final LoginAttemptLimiter limiter;
  private final byte[] signupCodeDigest;
  /** Compared against when the username doesn't exist, so a miss costs the same bcrypt work. */
  private final String dummyHash;

  public AdminAuthService(
      AdminUserRepository users,
      PasswordEncoder passwordEncoder,
      AdminTokenService tokens,
      LoginAttemptLimiter limiter,
      @Value("${admin.signup-code}") String signupCode) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.tokens = tokens;
    this.limiter = limiter;
    this.signupCodeDigest =
        signupCode == null || signupCode.isEmpty() ? null : sha256(signupCode);
    this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
  }

  public String signup(String clientIp, String username, String signupCode, String password) {
    if (limiter.isBlocked(Action.SIGNUP, clientIp)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
    }
    if (!signupCodeMatches(signupCode)) {
      limiter.recordFailure(Action.SIGNUP, clientIp);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, SIGNUP_REFUSED);
    }

    String normalized = normalizeUsername(username);
    if (!USERNAME.matcher(normalized).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Username must be 3-32 characters: lowercase letters, digits, '.', '_' or '-'");
    }
    validatePassword(password);
    if (users.existsByUsername(normalized)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is taken");
    }

    AdminUser user = new AdminUser();
    user.setUsername(normalized);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setCreatedAt(Instant.now());
    try {
      users.insert(user);
    } catch (DuplicateKeyException e) {
      // Concurrent sign-up with the same name; the unique index decided.
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username is taken");
    }
    return normalized;
  }

  public AdminTokenService.IssuedToken login(String clientIp, String username, String password) {
    if (limiter.isBlocked(Action.LOGIN, clientIp)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
    }
    Optional<AdminUser> user = users.findByUsername(normalizeUsername(username));
    String hash = user.map(AdminUser::getPasswordHash).orElse(dummyHash);
    boolean matches = password != null && passwordEncoder.matches(password, hash);
    if (user.isEmpty() || !matches) {
      limiter.recordFailure(Action.LOGIN, clientIp);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, LOGIN_FAILED);
    }
    return tokens.issue(user.get().getUsername());
  }

  /** Constant-time: both sides are hashed to fixed-length digests, then compared with isEqual. */
  private boolean signupCodeMatches(String submitted) {
    if (signupCodeDigest == null || submitted == null) {
      return false;
    }
    return MessageDigest.isEqual(signupCodeDigest, sha256(submitted));
  }

  static String normalizeUsername(String username) {
    return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
  }

  static void validatePassword(String password) {
    if (password == null
        || password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARS
        || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must be at least 12 characters and at most 72 bytes");
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
