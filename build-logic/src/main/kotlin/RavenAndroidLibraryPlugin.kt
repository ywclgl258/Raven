import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Shared defaults only; each module keeps its namespace, dependencies and special configuration. */
class RavenAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.library")

        target.extensions.configure<LibraryExtension> {
            compileSdk = 36
            buildToolsVersion = "36.0.0"

            defaultConfig {
                minSdk = 24
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            testOptions {
                unitTests.isReturnDefaultValues = true
            }
        }

        target.extensions.configure<KotlinAndroidProjectExtension> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}
