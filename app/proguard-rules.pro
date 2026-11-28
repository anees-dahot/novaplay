# libVLC is called from native code via JNI; keep its Java side intact.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**

# ExoPlayer loads its FFmpeg audio renderer by class name.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.**
