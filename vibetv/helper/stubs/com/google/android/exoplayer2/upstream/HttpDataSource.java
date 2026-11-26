package com.google.android.exoplayer2.upstream;
public interface HttpDataSource {
  class InvalidResponseCodeException extends java.io.IOException {
    public final int responseCode;
    InvalidResponseCodeException() { responseCode = 0; }
  }
}
