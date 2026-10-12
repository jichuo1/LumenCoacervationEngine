import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

abstract class PrepareLumenNotices : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notices: ConfigurableFileCollection
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdParty: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations
    @TaskAction fun prepare() {
        files.sync {
            into(outputDirectory)
            from(notices) { into("lumen/licenses") }
            from(thirdParty) { into("lumen/licenses/third_party") }
        }
    }
}
val prepareLumenNotices = tasks.register<PrepareLumenNotices>("prepareLumenNotices") {
    notices.from(rootProject.file("LICENSE"), rootProject.file("NOTICE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
    thirdParty.set(rootProject.layout.projectDirectory.dir("third_party"))
    outputDirectory.set(layout.buildDirectory.dir("generated/lumenNotices"))
}

group = providers.gradleProperty("lumen.group").get()
version = providers.gradleProperty("lumen.version").get()

android {
    namespace = "com.lumen.coacervation.engine.compose"
    compileSdk = providers.gradleProperty("lumen.compileSdk").get().toInt()
    buildToolsVersion = providers.gradleProperty("lumen.buildTools").get()
    defaultConfig {
        minSdk = providers.gradleProperty("lumen.minSdk").get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLumenNotices, PrepareLumenNotices::outputDirectory)
}
tasks.withType<Jar>().configureEach {
    if (name == "sourceReleaseJar") {
        from(rootProject.file("LICENSE"), rootProject.file("NOTICE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
        from(rootProject.file("third_party")) { into("third_party") }
    }
}

dependencies {
    api(project(":lumen-engine"))
    implementation(project(":lumen-motion"))
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    api(libs.compose.foundation)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "lumen-compose"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("lumen-compose")
                description.set("Optional native Compose drawing and interaction for the Lumen Coacervation Engine.")
                licenses { license { name.set("The Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
            }
        }
    }
    repositories { maven { name = "projectLocal"; url = uri(rootProject.layout.buildDirectory.dir("repo")) } }
}
