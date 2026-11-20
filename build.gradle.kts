// Root aggregator. Each sub-project owns its own build logic:
//   :backend  -> Spring Boot (Java 17)
//   :frontend -> React + TypeScript (Vite), driven through the node-gradle plugin

tasks.register("buildAll") {
    group = "build"
    description = "Builds backend jar and frontend bundle"
    dependsOn(":backend:build", ":frontend:build")
}
