package com.claimspipeline.claimsintake;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signing key, encoder and decoder for the admin session token: an HS256 JWT signed with
 * {@code ADMIN_TOKEN_SECRET} from .env. The decoder accepts HS256 only, so a token signed with
 * any other algorithm (or "none") is rejected.
 */
@Configuration
public class AdminTokenConfig {

  static final int MIN_SECRET_BYTES = 32;

  @Bean
  public SecretKey adminTokenKey(@Value("${admin.token-secret}") String secret) {
    byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      // Fail at startup rather than run with a weak or default secret.
      throw new IllegalStateException(
          "ADMIN_TOKEN_SECRET must be set in .env and be at least "
              + MIN_SECRET_BYTES
              + " bytes (e.g. `openssl rand -base64 48`)");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  public JwtEncoder adminTokenEncoder(SecretKey adminTokenKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(adminTokenKey));
  }

  @Bean
  public JwtDecoder adminTokenDecoder(SecretKey adminTokenKey) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(adminTokenKey).macAlgorithm(MacAlgorithm.HS256).build();
    // Expiry (with Spring's default 60 s clock skew) and issuer are both checked.
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(AdminTokenService.ISSUER));
    return decoder;
  }
}
