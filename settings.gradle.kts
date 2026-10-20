rootProject.name = "tether-push"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

// The SDK is built from source: tether-xmpp is a git submodule, included as a
// composite build, so `io.github.usmandurrany.tether:*` dependencies resolve
// to its modules.
val sdk = file("tether-xmpp")
if (!sdk.resolve("settings.gradle.kts").exists()) {
    throw GradleException(
        "The Tether SDK sources are missing. Run `git submodule update --init` " +
            "(or clone with `git clone --recursive`).",
    )
}
includeBuild(sdk)
