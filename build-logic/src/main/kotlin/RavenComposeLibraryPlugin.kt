import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Compose defaults shared by DesignSystem and Feature UI modules. */
class RavenComposeLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("raven.android.library")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        target.extensions.configure<LibraryExtension> {
            buildFeatures {
                compose = true
            }
        }

        target.extensions.configure<ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(
                target.rootProject.layout.projectDirectory.file("compose_stability.conf"),
            )
        }
    }
}
