# ============================================
# MediaPipe Face Landmarker ProGuard 规则
# ============================================

# 1. 保留 MediaPipe Tasks 核心类
-keep class com.google.mediapipe.tasks.core.** { *; }
-keep class com.google.mediapipe.tasks.components.** { *; }

# 2. 保留 FaceLandmarker 及其相关类
-keep class com.google.mediapipe.tasks.vision.facelandmarker.** { *; }
-keep class com.google.mediapipe.tasks.vision.core.** { *; }

# 3. 保留框架图像处理类
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.framework.image.** { *; }

# 4. 保留所有 Native 方法（JNI 调用必须）
-keepclasseswithmembernames class * {
    native <methods>;
}

# 5. 保留结果类和容器类
-keep class com.google.mediapipe.tasks.components.containers.** { *; }
-keep class com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult { *; }
-keep class com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult$** { *; }

# 6. 保留 Builder/Options 配置类
-keep class com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker$** { *; }
-keep class com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerOptions { *; }
-keep class com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerOptions$** { *; }

# 7. 保留 BaseOptions 及其 Builder
-keep class com.google.mediapipe.tasks.core.BaseOptions { *; }
-keep class com.google.mediapipe.tasks.core.BaseOptions$** { *; }

# 8. 保留 RunningMode 枚举
-keep class com.google.mediapipe.tasks.vision.core.RunningMode { *; }

# 9. 保留注解和签名
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# 10. 保留 Bitmap 相关
-keep class android.graphics.Bitmap { *; }

# ============================================
# CameraX ProGuard 规则
# ============================================
-keep class androidx.camera.** { *; }
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }

# ============================================
# Compose 规则（您的项目使用了 Compose）
# ============================================
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }

# ============================================
# 可选：如果将来开启混淆，保留您的业务类
# ============================================
# -keep class com.example.camera.** { *; }