pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "MBrain"
include(":app")
listOf("core", "device", "server-service", "shell-core", "root", "shizuku", "apps").forEach { module ->
    include(":droid-mcp-$module")
    project(":droid-mcp-$module").projectDir = file("vendor/droid-mcp/droid-mcp-$module")
}
