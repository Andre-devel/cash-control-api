plugins {
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.owasp.dependencycheck") version "12.1.3"
    java
}

group = "com.cashcontrol"
version = "0.0.1-SNAPSHOT"

// Bakes version/time/commit into META-INF/build-info.properties, which Spring Boot's
// ProjectInfoAutoConfiguration picks up as a BuildProperties bean — read by
// VersionController to expose GET /api/v1/version. GIT_COMMIT is set as a Docker
// build ARG (the builder stage has no .git dir to read it from directly).
springBoot {
    buildInfo {
        properties {
            additional.set(mapOf("commit" to (System.getenv("GIT_COMMIT") ?: "unknown")))
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

val jjwtVersion = "0.12.6"
val springdocVersion = "2.8.8"
val bouncycastleVersion = "1.80"
val logstashEncoderVersion = "8.0"
val testcontainersVersion = "1.21.0"
val pdfboxVersion = "3.0.7"

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // JWT (Spring Security OAuth2 JOSE — used for JWT signing/verification infrastructure)
    implementation("org.springframework.security:spring-security-oauth2-jose")

    // Flyway — schema migration (starter required in Spring Boot 4.x for auto-configuration)
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL JDBC driver
    runtimeOnly("org.postgresql:postgresql")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // JWT library — stateless token signing and validation
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:$jjwtVersion")

    // Argon2id password hashing (requires BouncyCastle crypto provider)
    implementation("org.bouncycastle:bcprov-jdk18on:$bouncycastleVersion")

    // OpenAPI / Swagger UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    // Structured JSON logging for observability stacks (ELK, Loki, CloudWatch)
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    // PDFBox — extração de texto das faturas de cartão em PDF. Única dependência nova
    // da importação de fatura: diferente do CSV do extrato, não há como ler um PDF em
    // JVM pura sem uma biblioteca.
    implementation("org.apache.pdfbox:pdfbox:$pdfboxVersion")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyCheck {
    failBuildOnCVSS = 9.0f
    suppressionFile = "owasp-suppressions.xml"
    nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
    analyzers.retirejs.enabled = false
}

tasks.withType<JavaCompile> {
    options.annotationProcessorPath = configurations.annotationProcessor.get()
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "3g"
}

fun loadDotEnv(): Map<String, String> {
    val envFile = rootProject.file(".env")
    if (!envFile.exists()) return emptyMap()
    return envFile.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && it.contains("=") }
        .associate { line ->
            val idx = line.indexOf("=")
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val env = loadDotEnv()
    environment(env)
    environment("SPRING_PROFILES_ACTIVE", env.getOrDefault("SPRING_PROFILES_ACTIVE", "dev"))
}