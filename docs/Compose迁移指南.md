# Typeink Compose 迁移指南

## 为什么迁移到 Jetpack Compose？

| 特性 | XML | Compose |
|------|-----|---------|
| 毛玻璃效果 | 模拟（半透明遮罩） | ✅ 真 blur(radius = 30.dp) |
| 弹性动画 | 复杂（ObjectAnimator） | ✅ spring() 原生支持 |
| 状态管理 | 繁琐（findViewById） | ✅ 声明式，自动重绘 |
| 代码量 | 多（XML + Kotlin） | 少（纯Kotlin） |
| 预览 | 静态 | ✅ 实时交互预览 |

## 迁移步骤

### 1. 添加依赖

```groovy
// build.gradle (app)
android {
    buildFeatures {
        compose true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation "androidx.compose.ui:ui:1.6.0"
    implementation "androidx.compose.material3:material3:1.2.0"
    implementation "androidx.compose.animation:animation:1.6.0"
    implementation "androidx.compose.ui:ui-tooling-preview:1.6.0"
}
```

### 2. 修改 InputMethodService

```kotlin
class TypeinkInputMethodService : InputMethodService() {
    
    override fun onCreateInputView(): View {
        return TypeinkComposeView(this).createView()
    }
}
```

### 3. 主要组件替换

| XML 组件 | Compose 等效 |
|---------|-------------|
| `FrameLayout` | `Box` |
| `LinearLayout` | `Column` / `Row` |
| `TextView` | `Text` |
| `ImageButton` | `IconButton` |
| `View` (背景) | `Modifier.background()` |

### 4. 关键效果实现

#### 毛玻璃背景（API 31+）
```kotlin
Modifier.blur(radius = 30.dp)
```

#### 弹性动画
```kotlin
val scale by animateFloatAsState(
    targetValue = if (isPressed) 1.1f else 1f,
    animationSpec = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
)
```

#### 渐变球体
```kotlin
.background(
    brush = Brush.radialGradient(
        colors = listOf(Color.White, Color.Gray),
        center = Offset(0.3f, 0.3f)
    ),
    shape = CircleShape
)
```

## 完整Compose实现

见 `android/app/src/main/java/com/typeink/compose/TypeinkComposeView.kt`

## 预计工作量

| 任务 | 时间 |
|------|------|
| 添加Compose依赖 | 10分钟 |
| 重写主界面 | 2-3小时 |
| 状态管理迁移 | 1-2小时 |
| 动画效果实现 | 2-3小时 |
| 测试调试 | 1-2小时 |
| **总计** | **1-2天** |

## 建议

1. **短期**：先测试 build2 修复版是否可用
2. **中期**：如果build2仍有问题，立即进行Compose迁移
3. **长期**：Compose是Android UI的未来，迁移有价值

## 参考

- [Compose Blur Documentation](https://developer.android.com/reference/kotlin/androidx/compose/ui/Modifier#(androidx.compose.ui.Modifier).blur(androidx.compose.ui.unit.Dp,androidx.compose.ui.draw.BlurredEdgeTreatment))
- [Spring Animation in Compose](https://developer.android.com/jetpack/compose/animation/customize#spring)
