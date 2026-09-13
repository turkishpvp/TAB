plugins {
    id("tab.parent")
}

allprojects {
    group = "me.neznamy"
    version = "6.2.0-SNAPSHOT"
    description = "An all-in-one solution that works"

    ext.set("id", "tab")
    ext.set("website", "https://github.com/NEZNAMY/TAB")
    ext.set("author", "NEZNAMY")
    ext.set("credits", "Joseph T. McQuigg (JT122406)")
}

val platformPaths = setOf(
    ":bukkit",
    ":bukkit:v1_8_R3",
    ":velocity"
)

val specialPaths = setOf(
    ":api",
    ":shared"
)

subprojects {
    when (path) {
        in platformPaths -> plugins.apply("tab.platform-conventions")
        in specialPaths -> plugins.apply("tab.standard-conventions")
        else -> plugins.apply("tab.base-conventions")
    }
}
