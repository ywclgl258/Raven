plugins {
    id("raven.compose.library")
}

android {
    namespace = "com.raven.app.core.designsystem"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.material3.adaptive)
    api(libs.androidx.compose.material3.adaptive.navigation.suite)
    api(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
