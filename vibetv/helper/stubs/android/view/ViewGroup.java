package android.view;
public class ViewGroup extends View implements ViewParent {
  public static class LayoutParams { public LayoutParams(int w, int h) {} }
  public static class MarginLayoutParams extends LayoutParams {
    public int topMargin, leftMargin, rightMargin, bottomMargin;
    public MarginLayoutParams(int w, int h) { super(w, h); }
  }
  public int indexOfChild(View v) { return 0; }
  public int getChildCount() { return 0; }
  public View getChildAt(int i) { return null; }
  public void addView(View v) {}
  public void addView(View v, int index) {}
}
