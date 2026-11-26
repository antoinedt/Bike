package android.content;
public abstract class Context {
  public abstract Context getApplicationContext();
  public abstract java.io.File getExternalFilesDir(String type);
  public abstract java.io.File getFilesDir();
  public abstract String getPackageName();
  public abstract ContentResolver getContentResolver();
}
