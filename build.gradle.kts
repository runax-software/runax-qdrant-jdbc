plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

group = "com.runaxsoftware"
version = "0.1.0-SNAPSHOT"

base {
    archivesName = "qdrant-jdbc"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val driverVersion = version.toString()
val moduleName = "com.runaxsoftware.qdrant.jdbc"

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

// The driver itself compiles warning-free.
tasks.compileJava {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    inputs.property("version", driverVersion)
    filesMatching("**/driver.properties") {
        expand("version" to driverVersion)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to moduleName,
            "Implementation-Title" to "Runax JDBC Driver for Qdrant",
            "Implementation-Version" to driverVersion,
        )
    }
}

// The jar for DataGrip and other JDBC clients: the driver plus Jackson, its only dependency.
// Tools often bring in another Jackson version, so it's relocated to keep them from clashing,
// and its service files are renamed to match.
tasks.shadowJar {
    val shaded = "com.runaxsoftware.qdrant.jdbc.shaded"
    relocate("com.fasterxml.jackson", "$shaded.jackson")
    mergeServiceFiles()
    exclude("**/module-info.class")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Integration tests run against a real Qdrant: start it with `docker compose up -d`. They also
// load the shaded jar on its own, the way DataGrip does, so it's built first.
tasks.register<Test>("integrationTest") {
    description = "Runs the integration tests against Qdrant (start it with docker compose up -d)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)

    val shadedJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(shadedJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dqdrant.jdbc.shadedJar=${shadedJar.get().asFile.absolutePath}")
    })
}

tasks.register<JavaExec>("seedQdrant") {
    description = "Loads the sample collections into Qdrant (start it with docker compose up -d)."
    group = "qdrant"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.runaxsoftware.qdrant.jdbc.support.SeedSampleData"
}
