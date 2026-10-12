plugins {
    alias(libs.plugins.app.android.library)
    alias(libs.plugins.app.android.publish)
}

android {
    namespace = "github.leavesczy.alphavideoplayer.exoplayer"
}

dependencies {
    api(project(":alphavideoplayer"))
    compileOnly(libs.androidx.media3.exoplayer)
}