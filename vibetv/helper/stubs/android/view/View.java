package android.view;
public class View {
  public static final int VISIBLE = 0, GONE = 8;
  public interface OnClickListener { void onClick(View v); }
  public android.content.Context getContext() { return null; }
  public <T extends View> T findViewById(int id) { return null; }
  public ViewParent getParent() { return null; }
  public void setVisibility(int v) {}
  public void setOnClickListener(OnClickListener l) {}
  public void setPadding(int l, int t, int r, int b) {}
  public android.content.res.Resources getResources() { return null; }
  public ViewGroup.LayoutParams getLayoutParams() { return null; }
  public void setLayoutParams(ViewGroup.LayoutParams p) {}
  public Object getTag(int key) { return null; }
  public void setTag(int key, Object tag) {}
}
