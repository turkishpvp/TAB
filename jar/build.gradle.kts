import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar

plugins {
    id("com.gradleup.shadow")
}

val platformPaths = setOf(
    ":bukkit",
    ":bukkit:v1_8_R3",
    ":velocity"
)

val platforms: List<Project> = platformPaths.map { rootProject.project(it) }

// Each platform's own build script must have run before its shadowJar task is looked up below,
// otherwise the platform is configured with only the conventions and loses its dependencies and Java release
platforms.forEach { evaluationDependsOn(it.path) }

tasks {
    shadowJar {
        archiveFileName.set("TAB v${project.version}.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        fun registerPlatform(project: Project, jarTask: AbstractArchiveTask) {
            dependsOn(jarTask)
            dependsOn(project.tasks.withType<Jar>())
            from(zipTree(jarTask.archiveFile))
        }

        platforms.forEach { p ->
            val task = p.tasks.named<ShadowJar>("shadowJar").get()
            registerPlatform(p, task)
        }
    }

    build.get().dependsOn(shadowJar)
}
