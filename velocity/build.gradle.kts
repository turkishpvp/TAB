dependencies {
    implementation(projects.shared)
    implementation("org.bstats:bstats-velocity:3.1.0")
    compileOnly("com.github.limework.redisbungee:RedisBungee-Velocity:0.11.0")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-proxy:3.4.0-SNAPSHOT")
    compileOnlyApi("net.kyori:adventure-nbt:4.17.0")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("com.github.LeonMangler:PremiumVanishAPI:2.9.0-4")
    compileOnly("net.william278:velocityscoreboardapi:2.0.0")
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:3.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.22.0")
    testImplementation("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.netty:netty-all:4.1.90.Final")
    testRuntimeOnly("net.luckperms:api:5.4")
    testRuntimeOnly("com.google.guava:guava:31.1-jre")
}

tasks.test {
    useJUnitPlatform()
}

tasks.compileJava {
    options.release.set(21)
}
