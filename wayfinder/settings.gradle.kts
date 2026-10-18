pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Orionstar AgentOS SDK (public credentials from the official SDK documentation)
        maven {
            credentials.username = "agentMaven"
            credentials.password = "agentMaven"
            url = uri("https://npm.ainirobot.com/repository/maven-public/")
        }
    }
}

rootProject.name = "Wayfinder"
include(":app")
