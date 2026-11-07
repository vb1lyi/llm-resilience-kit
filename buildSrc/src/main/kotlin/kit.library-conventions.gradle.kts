// Conventions for published kit-* modules: java-library + strict Javadoc (missing docs fail the build).

plugins {
    id("kit.java-conventions")
    `java-library`
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:all", "-quiet")
        addBooleanOption("Werror", true)
    }
}

tasks.check {
    dependsOn(tasks.javadoc)
}
