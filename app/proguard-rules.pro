# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Sherpa-ONNX resolves Java classes and native methods from JNI. Renaming or
# removing them breaks offline synthesis at runtime.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
-dontwarn ai.onnxruntime.**

# Keep line information so release crash reports remain actionable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile