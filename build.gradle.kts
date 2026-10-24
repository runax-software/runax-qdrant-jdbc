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
    implementation(libs.qdrant.client)
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

// The jar for DataGrip and other JDBC clients: the driver plus its dependencies. Libraries
// that tools often bring in other versions of (Jackson, Guava, protobuf, SLF4J) are relocated
// so they can't clash. gRPC and the Qdrant SDK keep their packages: gRPC's bundled Netty
// finds its native transport by package name.
tasks.shadowJar {
    val shaded = "com.runaxsoftware.qdrant.jdbc.shaded"
    relocate("com.fasterxml.jackson", "$shaded.jackson")
    relocate("com.google", "$shaded.google")
    relocate("io.perfmark", "$shaded.perfmark")
    relocate("org.slf4j", "$shaded.slf4j")
    // Shadow drops duplicate files by default, which would keep only one jar's gRPC service
    // file (losing the DNS name resolver). Let service files through so they get merged.
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    exclude("**/module-info.class")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}
