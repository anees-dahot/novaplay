# libVLC is called from native code via JNI; keep its Java side intact.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**
