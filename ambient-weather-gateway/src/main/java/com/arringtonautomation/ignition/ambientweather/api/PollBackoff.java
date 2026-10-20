package com.arringtonautomation.ignition.ambientweather.api;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a scheduled poll should actually call the API. After a failure the driver waits
 * progressively longer (interval x 2, x 4, ... capped at {@link #MAX_DELAY}) so a down server or a
 * 429 isn't hammered; bad keys go straight to the cap since retrying faster won't fix them.
 * Not thread-safe: the driver calls it only from its single poll thread.
 */
public final class PollBackoff {

  public static final Duration MAX_DELAY = Duration.ofMinutes(5);

  private final Duration interval;
  private int consecutiveFailures;
  private Instant nextAttempt = Instant.EPOCH;

  public PollBackoff(Duration interval) {
    if (interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive");
    }
    this.interval = interval;
  }

  public boolean shouldPoll(Instant now) {
    return !now.isBefore(nextAttempt);
  }

  public void onSuccess() {
    consecutiveFailures = 0;
    nextAttempt = Instant.EPOCH;
  }

  /** Records a failure and returns how long until the next attempt. */
  public Duration onFailure(Instant now, boolean authError) {
    consecutiveFailures++;
    Duration delay;
    if (authError) {
      delay = MAX_DELAY;
    } else {
      int shift = Math.min(consecutiveFailures, 16); // 2^16 x interval is far past the cap anyway
      delay = interval.multipliedBy(1L << shift);
      if (delay.compareTo(MAX_DELAY) > 0) {
        delay = MAX_DELAY.compareTo(interval) > 0 ? MAX_DELAY : interval;
      }
    }
    nextAttempt = now.plus(delay);
    return delay;
  }

  public int consecutiveFailures() {
    return consecutiveFailures;
  }
}
