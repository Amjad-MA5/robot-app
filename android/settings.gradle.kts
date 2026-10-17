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
        // Orionstar AgentOS SDK (öffentliche Zugangsdaten laut offizieller SDK-Doku)
        maven {
            credentials.username = "agentMaven"
            credentials.password = "agentMaven"
            url = uri("https://npm.ainirobot.com/repository/maven-public/")
        }
    }
}

rootProject.name = "SmartReceptionist"
include(":app")
