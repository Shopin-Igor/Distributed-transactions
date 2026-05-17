plugins {
    java
    application
}

group = "dtx"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("org.example.dtx.Main")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
