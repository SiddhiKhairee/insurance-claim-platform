package com.claimspipeline.claimsintake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who can call what.
 *
 * <ul>
 *   <li>{@code /claims/**}: public, as before this phase (submit, status, appeal). There are no
 *       claimant accounts, a deliberate demo scoping (PLAN.md §12).
 *   <li>{@code /api/admin/signup} and {@code /api/admin/login}: public, but gated by the signup
 *       code / password and rate limited.
 *   <li>Every other {@code /api/admin/**} endpoint: an admin token (role ADMIN). No or invalid
 *       token is 401; a valid token without the role is 403.
 *   <li>Anything else: denied.
 * </ul>
 *
 * <p>CSRF protection is off because authentication is a bearer token sent in the Authorization
 * header, never a cookie, so a cross-site request can't carry the admin's credentials. Sessions
 * are stateless for the same reason.
 */
@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/error")
                    .permitAll()
                    .requestMatchers("/claims", "/claims/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/admin/signup", "/api/admin/login")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(adminRolesConverter())));
    return http.build();
  }

  /**
   * Maps the token's {@code roles} claim to authorities ("ADMIN" becomes ROLE_ADMIN). Spring's
   * default reads {@code scope}/{@code scp}, which would leave every admin token without the role.
   */
  static JwtAuthenticationConverter adminRolesConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName("roles");
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
