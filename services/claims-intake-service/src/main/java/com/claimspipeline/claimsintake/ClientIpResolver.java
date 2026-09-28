package com.claimspipeline.claimsintake;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The client IP used by {@link LoginAttemptLimiter}.
 *
 * <p>In prod every request comes through nginx, so the TCP peer is always nginx. nginx sets
 * {@code X-Real-IP $remote_addr}, which overwrites anything the client sent, so that header is
 * trusted there ({@code admin.rate-limit.trust-proxy-header=true} in the prod compose file).
 * {@code X-Forwarded-For} is not used: nginx's {@code $proxy_add_x_forwarded_for} appends to
 * whatever the client sent, so a client could pick its own "IP". Everywhere else (dev, tests),
 * the header is ignored and the connection's remote address is used, because a client talking to
 * the service directly could set X-Real-IP itself.
 */
@Component
public class ClientIpResolver {

  private final boolean trustProxyHeader;

  public ClientIpResolver(@Value("${admin.rate-limit.trust-proxy-header}") boolean trustProxyHeader) {
    this.trustProxyHeader = trustProxyHeader;
  }

  public String resolve(HttpServletRequest request) {
    if (trustProxyHeader) {
      String realIp = request.getHeader("X-Real-IP");
      if (realIp != null && !realIp.isBlank()) {
        return realIp.strip();
      }
    }
    return request.getRemoteAddr();
  }
}
