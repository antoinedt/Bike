package android.view;
public abstract class LayoutInflater {
  public static LayoutInflater from(android.content.Context c) { return null; }
  public abstract View inflate(int res, ViewGroup root, boolean attach);
}
