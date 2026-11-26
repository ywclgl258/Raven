plugins {
    id("raven.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.raven.app.core.security"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:site-spi"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
