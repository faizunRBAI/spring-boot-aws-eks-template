plugins {
    java
    checkstyle
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.cyclonedx.bom") version "1.10.0"
}

group = "com.example"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Overriding a version the Spring Boot BOM manages.
//
// The BOM pins a driver version that was current when that Boot release shipped,
// and a security patch published afterwards does not reach you until the next
// Boot release does. The image scan blocks on exactly that gap, and this one line
// is the fix. Expect to add a line here occasionally; remove it once the BOM
// catches up.
extra["postgresql.version"] = "42.7.13"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")

    // Every supported database driver ships in the jar.
    //
    // The database module choice is made when the project is created, and it
    // only rewrites Terraform — it cannot reach into this file. Carrying all
    // five drivers (about 12 MB) is what lets one build serve postgres, mysql,
    // mariadb, oracle and both Aurora engines, with Spring selecting the driver
    // from the JDBC URL. Delete the ones you do not use once your database is
    // settled; see the licence note in .udap/docs/README.md for the Oracle one.
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
    runtimeOnly("com.oracle.database.jdbc:ojdbc11")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.flywaydb:flyway-mysql")
    runtimeOnly("org.flywaydb:flyway-database-oracle")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

checkstyle {
    toolVersion = "14.0.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    // The coding-standards gate blocks on any violation, warnings included.
    maxWarnings = 0
    isIgnoreFailures = false
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named("cyclonedxBom") {
    // One bill of materials feeds two gates: the SBOM artefact and the licence
    // allowlist check.
    setProperty("outputName", "sbom-source.cdx")
    setProperty("outputFormat", "json")
    setProperty("includeConfigs", listOf("runtimeClasspath"))
}
