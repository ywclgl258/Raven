import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseMetadataFile: File = rootProject.file("release.properties")
val releaseMetadata: Properties = Properties().apply {
    if (!releaseMetadataFile.isFile) {
        throw GradleException("Missing root release.properties at ${releaseMetadataFile.absolutePath}")
    }
    releaseMetadataFile.inputStream().use { load(it) }
}

val releaseVersionName: String = releaseMetadata.getProperty("versionName")?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: throw GradleException("release.properties must define non-blank versionName")

val releaseVersionCode: Int = releaseMetadata.getProperty("versionCode")?.trim()?.toIntOrNull()
    ?: throw GradleException("release.properties must define integer versionCode")

val expectedReleaseCertSha256: String = releaseMetadata.getProperty("releaseCertificate")?.trim()
    ?.replace(":", "")
    ?.uppercase(Locale.ROOT)
    ?.takeIf { it.matches(Regex("^[0-9A-F]{64}$")) }
    ?: throw GradleException("release.properties must define valid 64-hex releaseCertificate")

val signingHomeDir: File = System.getenv("RAVEN_SIGNING_HOME")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let(::File)
    ?: File(System.getProperty("user.home"), ".raven/signing")

val signingPropsFile: File = File(signingHomeDir, "signing.properties")

data class ExternalReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun loadExternalReleaseSigning(): ExternalReleaseSigning? {
    if (!signingPropsFile.isFile) return null
    val props = Properties().apply {
        signingPropsFile.inputStream().use { load(it) }
    }
    val rawStorePath = props.getProperty("KEYSTORE_PATH")?.trim().orEmpty()
    val storePassword = props.getProperty("KEYSTORE_PASSWORD")?.trim().orEmpty()
    val keyAlias = props.getProperty("KEY_ALIAS")?.trim().orEmpty()
    val keyPassword = props.getProperty("KEY_PASSWORD")?.trim().orEmpty()
    if (rawStorePath.isEmpty() || storePassword.isEmpty() || keyAlias.isEmpty() || keyPassword.isEmpty()) {
        return null
    }
    val resolvedStoreFile = File(rawStorePath).let { candidate ->
        if (candidate.isAbsolute) candidate else File(signingPropsFile.parentFile, rawStorePath)
    }
    return ExternalReleaseSigning(
        storeFile = resolvedStoreFile,
        storePassword = storePassword,
        keyAlias = keyAlias,
        keyPassword = keyPassword,
    )
}

val externalReleaseSigning: ExternalReleaseSigning? = loadExternalReleaseSigning()

android {
    namespace = "com.raven.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.raven.app"
        minSdk = 24
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$expectedReleaseCertSha256\"")
    }

    signingConfigs {
        if (externalReleaseSigning != null) {
            create("release") {
                storeFile = externalReleaseSigning.storeFile
                storePassword = externalReleaseSigning.storePassword
                keyAlias = externalReleaseSigning.keyAlias
                keyPassword = externalReleaseSigning.keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { releaseSigning ->
                signingConfig = releaseSigning
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

val validateReleaseSigningTask = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Validates external Raven release signing properties, keystore presence, and SHA-256 certificate match."
    doLast {
        if (!signingPropsFile.isFile) {
            throw GradleException(
                "Release signing properties not found at ${signingPropsFile.absolutePath}. " +
                    "Set RAVEN_SIGNING_HOME or place signing.properties under ~/.raven/signing/.",
            )
        }
        val signing = loadExternalReleaseSigning()
            ?: throw GradleException(
                "Incomplete signing.properties at ${signingPropsFile.absolutePath}. " +
                    "Required keys: KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.",
            )
        if (!signing.storeFile.isFile) {
            throw GradleException("Release keystore not found at ${signing.storeFile.absolutePath}.")
        }
        val keyStore = KeyStore.getInstance("PKCS12")
        signing.storeFile.inputStream().use { input ->
            keyStore.load(input, signing.storePassword.toCharArray())
        }
        val cert = keyStore.getCertificate(signing.keyAlias)
            ?: throw GradleException("Key alias '${signing.keyAlias}' not found in release keystore.")
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { b -> String.format(Locale.ROOT, "%02X", b) }
        if (!actualSha256.equals(expectedReleaseCertSha256, ignoreCase = true)) {
            throw GradleException(
                "Release keystore SHA-256 ($actualSha256) does not match release.properties " +
                    "releaseCertificate ($expectedReleaseCertSha256).",
            )
        }
        logger.lifecycle("validateReleaseSigning PASSED (alias=${signing.keyAlias}, SHA-256=$actualSha256)")
    }
}

tasks.matching {
    it.name in setOf("assembleRelease", "bundleRelease", "packageRelease")
}.configureEach {
    dependsOn(validateReleaseSigningTask)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:site-spi"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:media"))
    implementation(project(":sites:video"))
    implementation(project(":sites:comic"))
    implementation(project(":sites:gallery"))
    implementation(project(":feature:browse"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.koin.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
