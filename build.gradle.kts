plugins {
    java
}

group = "the.lion"
version = "1.0.0"

val paperApi = providers.gradleProperty("paperApi").getOrElse("1.21.8-R0.1-SNAPSHOT")
val javaRelease = providers.gradleProperty("javaRelease").getOrElse("21").toInt()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.rosewooddev.io/repository/public/") {
        content { includeGroup("org.black_ixx") }
    }
    maven("https://repo.extendedclip.com/releases/") {
        content { includeGroup("me.clip") }
    }
    maven("https://repo.nexomc.com/releases") {
        content { includeGroup("com.nexomc") }
    }
    maven("https://jitpack.io") {
        content { includeGroup("com.github.Nocsy-Workshop") }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly("org.black_ixx:playerpoints:3.3.5") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.12.3") { isTransitive = false }
    compileOnly("com.nexomc:nexo:1.29.1") { isTransitive = false }
    // Nexo is written in Kotlin; javac needs the Kotlin annotation classes to read its class files.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.0") { isTransitive = false }
    compileOnly("com.github.Nocsy-Workshop:mcpets:v4.1.11") { isTransitive = false }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:$paperApi")
    testImplementation("org.yaml:snakeyaml:2.4")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:3.5.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaRelease)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("PetShop.jar")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("petshop.testOutput", layout.buildDirectory.dir("menu-test-output").get().asFile.absolutePath)
    // DatabaseTest only runs with -PtestDb=jdbc:mariadb://host:port/db -PtestDbUser=.. -PtestDbPassword=..
    for (name in listOf("testDb", "testDbUser", "testDbPassword", "testDbLimitedUser")) {
        systemProperty("petshop.$name", providers.gradleProperty(name).getOrElse(""))
    }
}
