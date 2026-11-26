package android.content;
public interface SharedPreferences {
  boolean getBoolean(String key, boolean def);
  Editor edit();
  interface Editor { Editor putBoolean(String key, boolean v); void apply(); }
}
