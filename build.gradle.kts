plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "org.craftcore.stellaria"
version = "1.0.87"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.citizensnpcs.co/repo")                            // Citizens
    maven("https://repo.codemc.io/repository/maven-releases/")             // PacketEvents
    maven("https://repo.codemc.org/repository/maven-public")               // VaultUnlocked
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") // PlaceholderAPI
    maven("https://jitpack.io")                                             // NuVotifier
    maven("https://repo.onarandombox.com/content/groups/public/")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    compileOnly("net.citizensnpcs:citizensapi:2.0.35-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.9")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("net.luckperms:api:5.4")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("net.dv8tion:JDA:6.6.0")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("com.github.nuvotifier:nuvotifier:2.7.2")
    compileOnly("org.mvplugins.multiverse.core:multiverse-core:5.8.1")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    sourceCompatibility = "21"
    targetCompatibility = "21"
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"

    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.named("build") {
    doLast {
        copy {
            from(tasks.shadowJar.get().archiveFile)
            into("run/plugins")
        }
    }
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")
}



tasks {
    runServer {
        minecraftVersion("1.21.11")
    }
}
