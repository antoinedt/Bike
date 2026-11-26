package android.widget;
public class CompoundButton extends TextView {
  public CompoundButton(android.content.Context c) { super(c); }
  public interface OnCheckedChangeListener { void onCheckedChanged(CompoundButton b, boolean checked); }
  public void setChecked(boolean b) {}
  public boolean isChecked() { return false; }
  public void toggle() {}
  public void setOnCheckedChangeListener(OnCheckedChangeListener l) {}
}
