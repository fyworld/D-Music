plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.solara.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.solara.music"
        minSdk = 24
        targetSdk = 34
        versionCode = 73
        versionName = "1.5.0"
        // v1.5.0：本地歌曲文件夹化 + DTS 播放 + 下载管理页 + 分享 +
        // 声道平衡 + 缓存补全 + 通知封面缓存（r1-r22 迭代）
        // FFmpeg DTS 软解模块——只编 arm64-v8a
        // （现代手机全 arm64；armeabi-v7a 老设备已极少，省一半编译时间）
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // v1.4.18：双版本——full（标准版，含打赏）/ lite（纯净版，无打赏）。
    // 同一份代码，BuildConfig.DONATE_ENABLED 控制打赏入口显隐。
    flavorDimensions += "version"
    productFlavors {
        create("full") {
            dimension = "version"
            buildConfigField("boolean", "DONATE_ENABLED", "true")
        }
        create("lite") {
            dimension = "version"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "DONATE_ENABLED", "false")
        }
    }

    // v1.5.0：release 签名配置——沿用 debug keystore（与历史发版一致，
    // 保证老用户可直接升级）。keystore 备份在 G:\WorkBuddy\tools\dmusic-release.keystore
    // （C 盘 Deep Freeze 风险，正式 keystore 以 G 盘备份为准）。
    // store 密码 android / key 密码 android / alias androiddebugkey
    signingConfigs {
        create("release") {
            storeFile = file("G:/WorkBuddy/tools/dmusic-release.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // v1.4.43：Media3 1.2.1 → 1.3.1——修复多个 CacheDataSource/SimpleCache
    // 缺陷（缓存写坏概率下降），音频管线 bug 一并修复
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    // MP3 ID3v2 标签读写：下载后嵌入封面/歌词到文件
    implementation("com.mpatric:mp3agic:0.9.1")
    // v1.5.1 r26：自定义音源——QuickJS 沙箱执行 lx-music 生态的
    // 音源脚本（wang.harlon.quickjs 与洛雪音乐同款绑定库，Maven Central）
    implementation("wang.harlon.quickjs:wrapper-android:2.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
