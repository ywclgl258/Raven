pluginManagement {
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Raven"

include(
    ":app",
    ":core:model",
    ":core:common",
    ":core:site-spi",
    ":core:security",
    ":core:network",
    ":core:database",
    ":core:designsystem",
    ":core:media",
    ":sites:video",
    ":sites:comic",
    ":sites:gallery",
    ":feature:browse",
    ":feature:library",
    ":feature:player",
    ":feature:reader",
    ":feature:settings",
)
