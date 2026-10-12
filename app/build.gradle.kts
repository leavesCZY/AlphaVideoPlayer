plugins {
    alias(libs.plugins.app.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "github.leavesczy.alphavideoplayer.sample"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(project(":alphavideoplayer"))
    implementation(project(":exoplayer"))
}