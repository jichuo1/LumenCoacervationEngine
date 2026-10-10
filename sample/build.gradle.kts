import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lumen.coacervation.sample"
    compileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()
    buildToolsVersion = providers.gradleProperty("lumen.buildTools").get()

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
            // Explicit verification builds exercise R8; the published Demo
            // remains readable and keeps the same default packaging contract.
            isMinifyEnabled = providers.gradleProperty("lumen.verifySampleR8").orNull == "true"
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
    implementation(project(":lumen-effects"))
    implementation(project(":lumen-assets"))
    implementation(project(":lumen-assets-lottie"))
    implementation(project(":lumen-assets-pag"))
    implementation(project(":lumen-assets-rive"))

    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("com.tencent.tav:libpag:4.5.98-noffavc")
}
