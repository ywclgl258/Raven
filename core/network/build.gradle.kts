plugins {
    id("raven.android.library")
}

android {
    namespace = "com.raven.app.core.network"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:site-spi"))
    implementation(project(":core:security"))

    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}
