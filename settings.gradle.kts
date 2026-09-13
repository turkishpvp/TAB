enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        mavenCentral() // Netty, SnakeYaml, json-simple, Guava, Kyori event, bStats, AuthLib, LuckPerms
        maven("https://repo.viaversion.com/") // ViaVersion
        maven("https://repo.william278.net/releases/") // VelocityScoreboardAPI
        maven("https://repo.codemc.org/repository/nms/") // CraftBukkit + NMS
        maven("https://repo.papermc.io/repository/maven-public/") // paperweight, Velocity, Adventure, BungeeCord-API
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") // PlaceholderAPI
        maven("https://repo.opencollab.dev/maven-snapshots/") // Floodgate, Bungeecord-proxy
        maven("https://repo.purpurmc.org/snapshots") // Purpur
        maven("https://jitpack.io") // PremiumVanish, Vault, YamlAssist, RedisBungee
        maven("https://mvn.lib.co.nz/public") // LibsDisguises
        maven("https://repo.william278.net/velocity/") // Velocity-proxy
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "TAB"

include(":api")
include(":shared")
include(":velocity")
include(":bukkit")
include(":bukkit:v1_8_R3")
include(":jar")
