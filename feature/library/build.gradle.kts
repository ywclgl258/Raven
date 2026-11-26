plugins {
    id("raven.compose.library")
}

android {
    namespace = "com.raven.app.feature.library"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:site-spi"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:media"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
