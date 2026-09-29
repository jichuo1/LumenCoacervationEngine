import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lumen.coacervation.sample"
    compileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()

    defaultConfig {
        applicationId = "com.lumen.coacervation.sample"
        minSdk = providers.gradleProperty("lumen.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("lumen.targetSdk").get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("lumen.version").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        // 发布到 Release 页的演示包：不混淆（便于对照源码排查），用调试签名即可直接安装。
        // 签名材料不入库（.gitignore），所以不同构建机产出的包签名不同，覆盖安装前需先卸载旧包。
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // 冒烟测试直接跑发布出去的那个构建类型，而不是另一套 debug 包。
    testBuildType = "release"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":lumen-engine"))
    implementation(project(":lumen-controls"))
    implementation(project(":lumen-motion"))
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
