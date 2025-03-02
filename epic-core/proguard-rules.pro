# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/weishu/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-keepattributes Signature,SourceFile,LineNumberTable,Exceptions

-keep class com.taobao.android.dexposed.** {*;}
-keep class me.weishu.epic.art.** {*;}

# delete log in release mode.
-assumenosideeffects class utils.Logger {
          public static void i(...);
          public static void w(...);
          public static void d(...);
          public static void e(...);
}

-assumenosideeffects class utils.Debug {
          public static *** hexdump(...);
}