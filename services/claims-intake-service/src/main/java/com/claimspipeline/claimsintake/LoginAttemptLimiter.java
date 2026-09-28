package com.claimspipeline.claimsintake;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Throttles failed admin sign-up and login attempts per client IP: after {@value #MAX_FAILURES}
 * failures within {@link #WINDOW}, further attempts for that action are refused (429) until the
 * oldest failure ages out. Sign-up and login are counted separately. A success doesn't clear
 * earlier failures, so a correct guess can't be used to reset the count.
 *
 * <p>In-memory and per instance: it resets on restart and isn't shared between replicas. That's
 * acceptable for the single-instance demo (PLAN.md §12, 2026-09-28).
 */
@Component
public class LoginAttemptLimiter {

  static final int MAX_FAILURES = 5;
  static final Duration WINDOW = Duration.ofMinutes(15);

  /** What an attempt was for; each has its own count per IP. */
  public enum Action {
    SIGNUP,
    LOGIN
  }

  private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
  private final Clock clock;

  @Autowired
  public LoginAttemptLimiter() {
    this(Clock.systemUTC());
  }

  LoginAttemptLimiter(Clock clock) {
    this.clock = clock;
  }

  public boolean isBlocked(Action action, String clientIp) {
    Deque<Instant> recent = failures.get(key(action, clientIp));
    if (recent == null) {
      return false;
    }
    synchronized (recent) {
      prune(recent);
      return recent.size() >= MAX_FAILURES;
    }
  }

  public void recordFailure(Action action, String clientIp) {
    Deque<Instant> recent = failures.computeIfAbsent(key(action, clientIp), k -> new ArrayDeque<>());
    synchronized (recent) {
      prune(recent);
      recent.addLast(clock.instant());
    }
  }

  private void prune(Deque<Instant> recent) {
    Instant cutoff = clock.instant().minus(WINDOW);
    while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
      recent.removeFirst();
    }
  }

  private static String key(Action action, String clientIp) {
    return action + "|" + clientIp;
  }
}
