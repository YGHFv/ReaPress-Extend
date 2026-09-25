plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.YGHFv.ReaPressExtend"
    // miuix 0.9.4 → Compose 1.12.0 要求 compileSdk 37（允许高于 targetSdk，不影响运行时行为）。
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.YGHFv.ReaPressExtend"
        // libxposed api 102 的 AAR 自己声明 minSdkVersion 26，低于它 Gradle 会拒绝合并。
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 观察模式：system_server 的 hook 装上去但**永不投递**，只把判定结果写日志。
        //
        // 存在的理由是上线节奏：system_server 崩溃 = 开机循环，必须先在一个版本里确认
        // 「hook 装上了、通知流能读到、设备能正常开机」，下一个版本才敢真正投递。
        // 用 BuildConfig 而不是运行时开关：它编译期就固定，不会被设置项误开。
        //
        // 阶段 2 已用 true 在真机上验证通过（hook 装上、system_server 正常启动、看门狗标记 ok）。
        // 阶段 3 起改为 false：投递照做，但默认仍是「放行模式」（原通知照常出现，只额外发一条），
        // 真正吞掉原通知要等用户在界面上打开拦截开关。
        buildConfigField("boolean", "OBSERVE_ONLY", "false")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        // 模块自己的主界面（ExpressMainActivity）用 miuix（Compose Multiplatform 库）绘制；
        // 注入 system_server / 宿主进程的部分不参与 Compose。
        compose = true
    }

    buildTypes.configureEach {
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }
}

dependencies {
    // 模块主界面：miuix（HyperOS 风格 Compose UI 库）+ activity-compose 提供的 setContent。
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    // 顶栏/底栏毛玻璃。用 miuix 官方 blur 库（KernelSU 管理器同款），与 miuix-ui 同版本、
    // 同一家发布，不引入第三套图形栈。它声明 minSdk 33，低版本走运行时门禁，manifest 里放行合并。
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")

    // XposedService / XposedProvider / RemotePreferences / HookedTarget。
    // 必须是 implementation：这个 AAR 自带一个 <provider>，要让 manifest 合并进模块 APK，
    // 框架才能在模块 App 进程里把 XposedService 的 binder 递过来（设置读写就靠它）。
    implementation("io.github.libxposed:service:102.0.0")

    // 只存在于被注入的进程，绝不能打进模块 APK —— compileOnly 是硬要求，
    // 打成 implementation 会让模块进程加载到一份假的 API 实现。
    compileOnly("io.github.libxposed:api:102.0.0")

    testImplementation("junit:junit:4.13.2")
    // 单测里的 android.jar 是桩，org.json 在桩里只有签名没有实现（调用会抛 "not mocked"）。
    // 引入真实实现，序列化/反序列化这类纯逻辑才测得了。
    testImplementation("org.json:json:20240303")
}
