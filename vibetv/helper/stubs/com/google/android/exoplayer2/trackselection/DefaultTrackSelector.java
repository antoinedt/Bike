package com.google.android.exoplayer2.trackselection;
public class DefaultTrackSelector extends TrackSelector {
  public ParametersBuilder buildUponParameters() { return null; }
  public void setParameters(ParametersBuilder b) {}
  public static final class ParametersBuilder {
    public ParametersBuilder setForceLowestBitrate(boolean b) { return this; }
    public ParametersBuilder setForceHighestSupportedBitrate(boolean b) { return this; }
    public ParametersBuilder setMaxVideoSize(int w, int h) { return this; }
    public ParametersBuilder clearViewportSizeConstraints() { return this; }
  }
}
