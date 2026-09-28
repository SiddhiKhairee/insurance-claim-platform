package com.claimspipeline.claimsintake;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public admin endpoints: sign-up (gated by ADMIN_SIGNUP_CODE) and login. Every other
 * {@code /api/admin/**} endpoint requires an admin token (SecurityConfig). The API lives under
 * /api/admin so that /admin stays free for the frontend's admin pages behind the single nginx
 * origin.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

  private final AdminAuthService authService;
  private final ClientIpResolver clientIpResolver;

  public AdminAuthController(AdminAuthService authService, ClientIpResolver clientIpResolver) {
    this.authService = authService;
    this.clientIpResolver = clientIpResolver;
  }

  @PostMapping("/signup")
  public ResponseEntity<SignupResponse> signup(
      @RequestBody SignupRequest request, HttpServletRequest http) {
    String username =
        authService.signup(
            clientIpResolver.resolve(http),
            request.username(),
            request.signupCode(),
            request.password());
    return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(username));
  }

  @PostMapping("/login")
  public LoginResponse login(@RequestBody LoginRequest request, HttpServletRequest http) {
    AdminTokenService.IssuedToken issued =
        authService.login(clientIpResolver.resolve(http), request.username(), request.password());
    return new LoginResponse(issued.token(), issued.expiresAt());
  }

  public record SignupRequest(String username, String signupCode, String password) {}

  public record SignupResponse(String username) {}

  public record LoginRequest(String username, String password) {}

  public record LoginResponse(String token, Instant expiresAt) {}
}
