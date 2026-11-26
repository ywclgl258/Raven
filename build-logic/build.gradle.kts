plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("ravenAndroidLibrary") {
            id = "raven.android.library"
            implementationClass = "RavenAndroidLibraryPlugin"
        }
        register("ravenComposeLibrary") {
            id = "raven.compose.library"
            implementationClass = "RavenComposeLibraryPlugin"
        }
    }
}
