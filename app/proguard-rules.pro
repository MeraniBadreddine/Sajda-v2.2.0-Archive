# XML-inflated views and launcher activity only
-keep class com.sajda.ui.MainActivity { *; }
-keep class com.sajda.ui.CompassView {
  public <init>(android.content.Context);
  public <init>(android.content.Context, android.util.AttributeSet);
  public <init>(android.content.Context, android.util.AttributeSet, int);
}
