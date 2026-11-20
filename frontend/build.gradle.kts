import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    // default: use the Node.js on PATH; pass -PnodeDownload=true (or set it in gradle.properties)
    // to let Gradle download the pinned version for fully reproducible CI builds
    download.set(providers.gradleProperty("nodeDownload").map { it.toBoolean() }.orElse(false))
    version.set("22.12.0")
}

val npmBuild by tasks.registering(NpmTask::class) {
    group = "build"
    description = "Type-checks (tsc) and bundles (Vite) the React app into dist/"
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.files("package.json", "package-lock.json", "tsconfig.json", "vite.config.ts", "index.html")
    outputs.dir("dist")
}

tasks.register<NpmTask>("npmDev") {
    group = "application"
    description = "Runs the Vite dev server on http://localhost:5173 (proxies /api to :8080)"
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "dev"))
}

tasks.assemble { dependsOn(npmBuild) }
tasks.clean { delete("dist") }
