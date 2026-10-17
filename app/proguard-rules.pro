# libwebrtc calls into its Java classes from native code by name (JNI), which R8 cannot see.
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-dontwarn org.webrtc.**

# Shrink, but keep names: crash reports from testers stay readable without a mapping file,
# and renaming would save little next to libwebrtc.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
