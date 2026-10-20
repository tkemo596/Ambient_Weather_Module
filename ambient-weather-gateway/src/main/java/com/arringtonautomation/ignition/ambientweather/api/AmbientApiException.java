package com.arringtonautomation.ignition.ambientweather.api;

/**
 * A failed Ambient Weather API call. Messages never contain the request URL, because the API
 * carries both keys as query parameters.
 */
public class AmbientApiException extends Exception {

  private static final long serialVersionUID = 1L;

  /** HTTP status, or -1 when the request never got a response (timeout, DNS, TLS, parse error). */
  private final int statusCode;

  public AmbientApiException(String message, int statusCode) {
    super(message);
    this.statusCode = statusCode;
  }

  public AmbientApiException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = -1;
  }

  public int statusCode() {
    return statusCode;
  }

  /** 429: over 1 req/s per apiKey or 3 req/s per applicationKey. */
  public boolean isRateLimited() {
    return statusCode == 429;
  }

  /** 401/403: a key is wrong, revoked, or the pair doesn't belong together. */
  public boolean isAuthError() {
    return statusCode == 401 || statusCode == 403;
  }
}
