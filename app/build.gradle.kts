import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

configure<ApplicationExtension> {
    namespace = "io.github.aoihoshino.realcugan_android_sdk"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.aoihoshino.realcugan_android_sdk"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 保留完整的 .so 符号，方便 ndk-stack 符号化 tombstone
        ndk {
            debugSymbolLevel = "FULL"
        }

        externalNativeBuild {
            cmake {
                // 传给 CMake 的参数列表
                arguments("-DANDROID_TOOLCHAIN=clang", "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Debug")
                // 额外的编译标志（-g 开启调试符号）
                cppFlags("-g")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isJniDebuggable = true
            externalNativeBuild {
                cmake {
                    // Debug 构建再确认一次
                    arguments += "-DCMAKE_BUILD_TYPE=Debug"
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":realcugan-android-sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}