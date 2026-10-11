# R8 / ProGuard 规则

# 入口 Activity 必须保留（AGP 默认已处理，这里写明意图）
-keep class com.nhyzcms.clubapp.MainActivity { *; }

# 后续若使用 @JavascriptInterface 桥接，注解方法的名称不能被混淆
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# release 包剔除日志输出（减少敏感信息泄露面）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# 保留行号信息（便于看崩溃堆栈，不保留局部变量名）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
