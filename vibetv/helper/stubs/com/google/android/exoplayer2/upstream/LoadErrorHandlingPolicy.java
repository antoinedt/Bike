package com.google.android.exoplayer2.upstream;
public interface LoadErrorHandlingPolicy {
  final class LoadErrorInfo {
    public final int errorCount;
    public final java.io.IOException exception;
    LoadErrorInfo() { errorCount = 0; exception = null; }
  }
}
