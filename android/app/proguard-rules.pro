# Keep JavaScript interface methods callable from the WebView.
-keepclassmembers class com.sml.bassindispersion.** {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView with JS enabled — preserve the standard JS bridge plumbing.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
