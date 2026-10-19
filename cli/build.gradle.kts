import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
}

application {
    // 生成的可执行脚本名（./gradlew :cli:installDist 后为 bin/aigw），与帮助文本、文档中的命令名一致。
    applicationName = "aigw"
    mainClass.set("dev.aigw.cli.MainKt")
}
