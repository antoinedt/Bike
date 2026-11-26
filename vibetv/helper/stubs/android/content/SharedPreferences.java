package android.content;
public interface SharedPreferences {
  boolean getBoolean(String key, boolean def);
  String getString(String key, String def);
  Editor edit();
  interface Editor { Editor putBoolean(String key, boolean v); Editor putString(String key, String v); void apply(); }
}
