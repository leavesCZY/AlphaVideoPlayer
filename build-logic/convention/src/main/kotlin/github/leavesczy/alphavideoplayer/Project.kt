package github.leavesczy.alphavideoplayer

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.configureAndroidProject(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk {
            version = release(version = androidCompileSdkVersion())
        }
        buildToolsVersion = androidBuildToolsVersion()
        defaultConfig.apply {
            minSdk {
                version = release(version = androidMinSdkVersion())
            }
        }
        buildFeatures.apply {
            buildConfig = false
        }
        lint.apply {
            checkDependencies = true
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.value(JvmTarget.JVM_21)
        }
    }
}
