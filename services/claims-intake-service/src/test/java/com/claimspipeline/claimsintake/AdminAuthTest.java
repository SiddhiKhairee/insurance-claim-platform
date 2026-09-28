package com.claimspipeline.claimsintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin sign-up, login, rate limiting and token checks through the real SecurityConfig, token
 * config and auth service. Each test uses its own client IP so the shared rate limiter doesn't
 * leak between tests.
 */
@WebMvcTest({AdminAuthController.class, AdminAppealController.class})
@Import({
  SecurityConfig.class,
  AdminTokenConfig.class,
  AdminTokenService.class,
  AdminAuthService.class,
  LoginAttemptLimiter.class,
  ClientIpResolver.class
})
class AdminAuthTest {

  private static final String CODE = "test-signup-code";
  private static final String PASSWORD = "correct-horse-battery";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private SecretKey adminTokenKey;
  @Autowired private JwtDecoder jwtDecoder;

  @MockBean private AdminUserRepository users;
  @MockBean private AdminAppealService appealService;

  // --- sign-up ---

  @Test
  void signupWithWrongCodeIsForbiddenAndCreatesNothing() throws Exception {
    signup("10.0.1.1", "admin.one", "wrong-code", PASSWORD)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Sign-up not allowed"));
    verify(users, never()).insert(any(AdminUser.class));
  }

  @Test
  void signupWithoutCodeIsForbidden() throws Exception {
    signup("10.0.1.2", "admin.one", null, PASSWORD).andExpect(status().isForbidden());
    verify(users, never()).insert(any(AdminUser.class));
  }

  /** Unset/empty ADMIN_SIGNUP_CODE: sign-up is disabled, never open, even for an empty code. */
  @Test
  void emptyConfiguredCodeDisablesSignup() {
    AdminAuthService disabled =
        new AdminAuthService(
            users, passwordEncoder, mock(AdminTokenService.class), new LoginAttemptLimiter(), "");

    for (String attempt : new String[] {"", null, "anything"}) {
      assertThatThrownBy(() -> disabled.signup("10.0.1.3", "admin.one", attempt, PASSWORD))
          .isInstanceOf(ResponseStatusException.class)
          .extracting(e -> ((ResponseStatusException) e).getStatusCode())
          .isEqualTo(HttpStatus.FORBIDDEN);
    }
    verify(users, never()).insert(any(AdminUser.class));
  }

  @Test
  void signupWithCorrectCodeStoresABcryptHashNotThePassword() throws Exception {
    when(users.insert(any(AdminUser.class))).thenAnswer(inv -> inv.getArgument(0));

    signup("10.0.1.4", "Admin.One", CODE, PASSWORD)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value("admin.one"));

    ArgumentCaptor<AdminUser> saved = ArgumentCaptor.forClass(AdminUser.class);
    verify(users).insert(saved.capture());
    assertThat(saved.getValue().getUsername()).isEqualTo("admin.one");
    assertThat(saved.getValue().getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
    assertThat(passwordEncoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
  }

  @Test
  void duplicateUsernameIsConflict() throws Exception {
    when(users.existsByUsername("admin.one")).thenReturn(true);

    signup("10.0.1.5", "admin.one", CODE, PASSWORD).andExpect(status().isConflict());
    verify(users, never()).insert(any(AdminUser.class));
  }

  @Test
  void invalidUsernameIsBadRequest() throws Exception {
    signup("10.0.1.6", "ab", CODE, PASSWORD).andExpect(status().isBadRequest());
    signup("10.0.1.6", "has space", CODE, PASSWORD).andExpect(status().isBadRequest());
    verify(users, never()).insert(any(AdminUser.class));
  }

  @Test
  void passwordLengthBoundsAreEnforcedInCharactersAndBytes() throws Exception {
    when(users.insert(any(AdminUser.class))).thenAnswer(inv -> inv.getArgument(0));

    signup("10.0.1.7", "short.pw", CODE, "a".repeat(11)).andExpect(status().isBadRequest());
    signup("10.0.1.7", "twelve.pw", CODE, "a".repeat(12)).andExpect(status().isCreated());
    // 36 x "é" is 36 characters and exactly 72 UTF-8 bytes: accepted.
    signup("10.0.1.7", "bytes.ok", CODE, "é".repeat(36)).andExpect(status().isCreated());
    // 37 x "é" is only 37 characters but 74 bytes: over bcrypt's 72-byte limit, rejected.
    signup("10.0.1.7", "bytes.over", CODE, "é".repeat(37)).andExpect(status().isBadRequest());
    signup("10.0.1.7", "ascii.over", CODE, "a".repeat(73)).andExpect(status().isBadRequest());
  }

  // --- login ---

  @Test
  void loginIssuesAnAdminTokenForTheRightPassword() throws Exception {
    givenAdmin("admin.one");

    String body =
        login("10.0.2.1", "Admin.One", PASSWORD)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.expiresAt").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String token = objectMapper.readTree(body).get("token").asText();
    var jwt = jwtDecoder.decode(token);
    assertThat(jwt.getSubject()).isEqualTo("admin.one");
    assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
    assertThat(jwt.getExpiresAt()).isBefore(Instant.now().plus(2, ChronoUnit.HOURS).plusSeconds(5));
  }

  @Test
  void loginFailuresAreGeneric() throws Exception {
    givenAdmin("admin.one");

    login("10.0.2.2", "admin.one", "wrong-password-123")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Invalid username or password"));
    login("10.0.2.2", "nobody", PASSWORD)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Invalid username or password"));
  }

  // --- rate limiting (amendment 1) ---

  @Test
  void sixthSignupAttemptIsRateLimitedEvenWithTheCorrectCode() throws Exception {
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
      signup("10.0.3.1", "admin.one", "wrong-" + i, PASSWORD).andExpect(status().isForbidden());
    }
    signup("10.0.3.1", "admin.one", CODE, PASSWORD)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error").value("Too many attempts. Try again later."));
    verify(users, never()).insert(any(AdminUser.class));

    // Another client is unaffected.
    when(users.insert(any(AdminUser.class))).thenAnswer(inv -> inv.getArgument(0));
    signup("10.0.3.2", "admin.one", CODE, PASSWORD).andExpect(status().isCreated());
  }

  @Test
  void sixthLoginAttemptIsRateLimitedEvenWithTheCorrectPassword() throws Exception {
    givenAdmin("admin.one");
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
      login("10.0.3.3", "admin.one", "wrong-password-" + i).andExpect(status().isUnauthorized());
    }
    login("10.0.3.3", "admin.one", PASSWORD).andExpect(status().isTooManyRequests());
  }

  /** trust-proxy-header is off here (as in dev), so a client can't dodge the limit via X-Real-IP. */
  @Test
  void spoofedRealIpHeaderIsIgnoredWhenProxyHeaderIsNotTrusted() throws Exception {
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
      mockMvc
          .perform(
              post("/api/admin/signup")
                  .with(ip("10.0.3.4"))
                  .header("X-Real-IP", "203.0.113." + i)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(Map.of("username", "x.y.z", "signupCode", "bad", "password", PASSWORD))))
          .andExpect(status().isForbidden());
    }
    mockMvc
        .perform(
            post("/api/admin/signup")
                .with(ip("10.0.3.4"))
                .header("X-Real-IP", "203.0.113.99")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", "x.y.z", "signupCode", CODE, "password", PASSWORD))))
        .andExpect(status().isTooManyRequests());
  }

  // --- tokens and roles (amendment 6) ---

  @Test
  void adminEndpointWithoutTokenIsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/admin/appeals")).andExpect(status().isUnauthorized());
  }

  @Test
  void garbageTokenIsUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/admin/appeals").header("Authorization", "Bearer not-a-jwt"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validlySignedTokenWithoutAdminRoleIsForbidden() throws Exception {
    String viewer = token(adminTokenKey, MacAlgorithm.HS256, List.of("VIEWER"), 60, "claims-intake-service");
    String noRoles = token(adminTokenKey, MacAlgorithm.HS256, null, 60, "claims-intake-service");

    withToken(viewer).andExpect(status().isForbidden());
    withToken(noRoles).andExpect(status().isForbidden());
  }

  @Test
  void expiredTokenIsUnauthorized() throws Exception {
    String expired =
        token(adminTokenKey, MacAlgorithm.HS256, List.of("ADMIN"), -300, "claims-intake-service");
    withToken(expired).andExpect(status().isUnauthorized());
  }

  @Test
  void tokenSignedWithAnotherKeyOrAlgorithmOrIssuerIsUnauthorized() throws Exception {
    SecretKey otherKey =
        new SecretKeySpec("a-different-secret-that-is-long-enough-0123456789".getBytes(), "HmacSHA256");
    withToken(token(otherKey, MacAlgorithm.HS256, List.of("ADMIN"), 60, "claims-intake-service"))
        .andExpect(status().isUnauthorized());
    // Same key, but HS384: the decoder accepts HS256 only.
    withToken(token(adminTokenKey, MacAlgorithm.HS384, List.of("ADMIN"), 60, "claims-intake-service"))
        .andExpect(status().isUnauthorized());
    withToken(token(adminTokenKey, MacAlgorithm.HS256, List.of("ADMIN"), 60, "someone-else"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminTokenFromLoginIsAccepted() throws Exception {
    givenAdmin("admin.one");
    when(appealService.list(null)).thenReturn(List.of());
    String body =
        login("10.0.4.1", "admin.one", PASSWORD).andReturn().getResponse().getContentAsString();
    String token = objectMapper.readTree(body).get("token").asText();

    withToken(token).andExpect(status().isOk());
  }

  // --- helpers ---

  private void givenAdmin(String username) {
    AdminUser user = new AdminUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(PASSWORD));
    when(users.findByUsername(username)).thenReturn(Optional.of(user));
  }

  private ResultActions signup(String ip, String username, String code, String password)
      throws Exception {
    Map<String, String> body = new java.util.HashMap<>();
    body.put("username", username);
    body.put("signupCode", code);
    body.put("password", password);
    return mockMvc.perform(
        post("/api/admin/signup")
            .with(ip(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)));
  }

  private ResultActions login(String ip, String username, String password) throws Exception {
    return mockMvc.perform(
        post("/api/admin/login")
            .with(ip(ip))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("username", username, "password", password))));
  }

  private ResultActions withToken(String token) throws Exception {
    return mockMvc.perform(get("/api/admin/appeals").header("Authorization", "Bearer " + token));
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  private static RequestPostProcessor ip(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  private static String token(
      SecretKey key, MacAlgorithm alg, List<String> roles, long expiresInSeconds, String issuer) {
    Instant now = Instant.now();
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("someone")
            .issuedAt(now.minusSeconds(600))
            .expiresAt(now.plusSeconds(expiresInSeconds));
    if (roles != null) {
      claims.claim("roles", roles);
    }
    return new NimbusJwtEncoder(new ImmutableSecret<>(key))
        .encode(JwtEncoderParameters.from(JwsHeader.with(alg).build(), claims.build()))
        .getTokenValue();
  }
}
