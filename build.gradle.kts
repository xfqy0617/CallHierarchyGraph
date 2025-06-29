plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.23"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "org.xfqy"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2022.1.1")
    type.set("IC") // Target IDE Platform
    plugins.set(listOf("java", "com.intellij.java"))
    updateSinceUntilBuild.set(true)
}

tasks {
    // [核心修改] Set the JVM compatibility versions to Java 11
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
    }

    patchPluginXml {
        sinceBuild.set("221")
        untilBuild.set("242.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}

dependencies {
//    // 告诉 Gradle，当我们引入 graphviz-java 时，不要引入它附带的 slf4j-api 包
////    implementation("guru.nidi:graphviz-java:0.18.1")
//    implementation("guru.nidi:graphviz-java:0.18.1") {
//        exclude(group = "org.slf4j", module = "slf4j-api")
//        // 如果 graphviz-java 还依赖了具体的 slf4j 实现（比如 slf4j-simple），也一并排除
//        // 通常排除 api 就足够了，但为了保险可以都加上
//        // exclude(group = "org.slf4j", module = "slf4j-simple")
//    }

    // JSON 序列化库
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // --- Lombok 依赖 ---
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    // --- 测试依赖 ---
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testCompileOnly("org.projectlombok:lombok:1.18.30")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.30")
}