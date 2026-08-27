# youtubedl-android parses yt-dlp's JSON output with Jackson into POJOs, so the
# model classes and their fields must survive shrinking or every field comes
# back null.
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keep class com.yausername.youtubedl_android.** { *; }

-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
