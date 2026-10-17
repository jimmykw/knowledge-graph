plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.0.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.7"))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation(platform("org.springaicommunity:spring-ai-agent-utils-bom:0.10.0"))
    implementation(platform("org.springaicommunity:typesafe-bom:0.4.0"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-pdf-document-reader")
    implementation("org.neo4j.driver:neo4j-java-driver")
    runtimeOnly("com.h2database:h2")
    implementation("io.vavr:vavr:1.0.1")
    implementation("org.springaicommunity:spring-ai-starter-typesafe")
    implementation("org.springaicommunity:typesafe-spring-ai")
    implementation("org.springaicommunity:spring-ai-agent-utils")

    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testCompileOnly("org.projectlombok:lombok:1.18.46")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.46")
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.test {
    // The opt-in real-Neo4j suite has its own task; keep `test` hermetic.
    filter { excludeTestsMatching("*IT") }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required = true
        xml.required = true
    }
}

tasks.register<org.gradle.api.tasks.testing.Test>("neo4jIt") {
    group = "verification"
    description = "Runs the *IT tests against the dedicated throwaway Neo4j (default bolt://localhost:7688)."
    systemProperty("neo4j.it", "true")
    listOf("NEO4J_IT_URI", "NEO4J_IT_USER", "NEO4J_IT_PASSWORD")
        .forEach { name -> System.getenv(name)?.let { value -> environment(name, value) } }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*IT") }
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    extensions.configure<JacocoTaskExtension> {
        setDestinationFile(layout.buildDirectory.file("jacoco/neo4jIt.exec").get().asFile)
    }
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.register<JacocoReport>("jacocoMergedReport") {
    group = "verification"
    description = "Merges test.exec and neo4jIt.exec (whichever exist) into build/reports/jacoco/merged."
    // Report-only: orders after the test tasks when both are requested, but never triggers them.
    mustRunAfter(tasks.test, "neo4jIt")
    executionData(fileTree(layout.buildDirectory.dir("jacoco")) { include("test.exec", "neo4jIt.exec") })
    sourceSets(sourceSets.main.get())
    reports {
        html.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/merged")
        xml.required = true
        xml.outputLocation = layout.buildDirectory.file("reports/jacoco/merged/jacoco.xml")
    }
}

tasks.register<org.gradle.api.tasks.testing.Test>("chatEval") {
    group = "verification"
    systemProperty("chat.eval", "true")
    // Forward -Deval.* (e.g. -Deval.factcheck=true, -Deval.judge.model=...) from the Gradle JVM to the test JVM.
    listOf("eval.factcheck", "eval.judge.base-url", "eval.judge.api-key", "eval.judge.model", "eval.judge.timeout")
        .forEach { name -> System.getProperty(name)?.let { value -> systemProperty(name, value) } }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.register<org.gradle.api.tasks.testing.Test>("routingEval") {
    group = "verification"
    systemProperty("routing.eval", "true")
    System.getProperty("routing.threshold")?.let { systemProperty("routing.threshold", it) }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*RoutingEvalTest") }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.register<org.gradle.api.tasks.testing.Test>("judgeEval") {
    group = "verification"
    systemProperty("judge.eval", "true")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*JudgeEvalTest") }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// A JavaExec, not a Test task: it writes to the real graph. Plan mode (default) migrates and writes build/reports/resolve/plan.*;
// -Dresolve.apply=true -Dresolve.backupConfirmed=true applies the plan. -Dresolve.* is forwarded to the JVM.
tasks.register<JavaExec>("resolveGraph") {
    group = "application"
    description = "One-time entity-resolution cleanup of the existing graph (plan by default; see AGENTS.md)."
    mainClass = "net.jimmykw.knowledgegraph.resolve.ResolveGraphCommand"
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = projectDir
    System.getProperties().stringPropertyNames()
        .filter { name -> name.startsWith("resolve.") }
        .forEach { name -> systemProperty(name, System.getProperty(name)) }
}

val nodeOnPath =(System.getenv("PATH") ?: "").split(File.pathSeparator).any { dir -> File(dir, "node").canExecute() }

tasks.register<Exec>("jsTest") {
    group = "verification"
    description = "Runs the UI tests in src/test/js with the Node built-in test runner (skipped when node is not on the PATH)."
    inputs.dir("src/test/js")
    inputs.dir("src/main/resources/static/js")
    outputs.upToDateWhen { false }
    // Node expands the glob itself; Exec does not go through a shell.
    commandLine("node", "--test", "src/test/js/*.test.js")
    onlyIf {
        if (!nodeOnPath) {
            logger.lifecycle("jsTest skipped: node is not on the PATH")
        }
        nodeOnPath
    }
}

tasks.check {
    dependsOn("jsTest")
}
