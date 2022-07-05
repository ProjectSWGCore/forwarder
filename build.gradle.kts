plugins {
	idea
	`java-library`
	kotlin("jvm")
}

val javaVersion: String by ext
val javaMajorVersion: String by ext
val kotlinTargetJdk: String by ext

java {
	modularity.inferModulePath.set(true)
}

idea {
	targetVersion = javaVersion
    module {
        inheritOutputDirs = true
    }
}

repositories {
	maven("https://dev.joshlarson.me/maven2")
	mavenCentral()
}

sourceSets {
	main {
		dependencies {
			implementation(group="org.jetbrains", name="annotations", version="20.1.0")
			implementation(project(":pswgcommon"))
			api(group="me.joshlarson", name="jlcommon-network", version="1.1.1")
			implementation(group="me.joshlarson", name="websocket", version="0.9.3")
		}
	}
	test {
		dependencies {
			implementation(kotlin("stdlib"))
			implementation(group="junit", name="junit", version="4.12")
		}
	}
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
	kotlinOptions {
		jvmTarget = kotlinTargetJdk
	}
	destinationDirectory.set(File(destinationDirectory.get().asFile.path.replace("kotlin", "java")))
}
