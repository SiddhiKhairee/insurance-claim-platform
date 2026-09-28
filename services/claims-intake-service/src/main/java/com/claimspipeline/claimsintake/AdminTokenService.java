package com.claimspipeline.claimsintake;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues admin session tokens: {@code sub} = username, {@code roles} = [ADMIN], short expiry. */
@Service
public class AdminTokenService {

  static final String ISSUER = "claims-intake-service";

  private final JwtEncoder encoder;
  private final Duration ttl;
  private final Clock clock;

  @Autowired
  public AdminTokenService(JwtEncoder encoder, @Value("${admin.token-ttl}") Duration ttl) {
    this(encoder, ttl, Clock.systemUTC());
  }

  AdminTokenService(JwtEncoder encoder, Duration ttl, Clock clock) {
    this.encoder = encoder;
    this.ttl = ttl;
    this.clock = clock;
  }

  public IssuedToken issue(String username) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(username)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("roles", List.of("ADMIN"))
            .build();
    String token =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return new IssuedToken(token, expiresAt);
  }

  public record IssuedToken(String token, Instant expiresAt) {}
}
