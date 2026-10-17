# youtubedl-android parses yt-dlp's JSON output with Jackson into POJOs, so the
# model classes and their fields must survive shrinking or every field comes
# back null.
-keep class com.yausername.youtubedl_android.mapper.** { *; }
-keep class com.yausername.youtubedl_android.** { *; }
# The library's shared module and the ffmpeg add-on: init code that unpacks the
# bundled runtimes, reached the same way.
-keep class com.yausername.** { *; }

# Jackson reads generic types and annotations through reflection; R8's full
# mode strips these attributes unless asked not to.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
}
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.**

# youtubedl-android unpacks its bundled Python with commons-compress, which
# creates its zip extra-field handlers by reflection. Shrunk, the first launch
# of a release build died in that unpacking with only an obfuscated class name
# ("Q2.e") for an error. commons-io is used alongside it.
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**
