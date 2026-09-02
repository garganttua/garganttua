# Garganttua Script Maven Plugin

## Description

Maven plugin to build JARs that can be dynamically included in Garganttua scripts (`.gs` files).

This plugin adds the `Garganttua-Packages` attribute to your JAR's manifest, enabling the Garganttua script engine to:
1. Load the JAR at runtime using `include("path/to/plugin.jar")`
2. Automatically discover packages containing Garganttua annotations
3. Rebuild the application context to integrate new expressions, beans, and builders

## Installation

<!-- AUTO-GENERATED-START -->
### Installation with Maven
```xml
<dependency>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-script-maven-plugin</artifactId>
    <version>3.0.0-ALPHA16</version>
</dependency>
```

### Actual version
3.0.0-ALPHA16

### Dependencies
 - `org.apache.maven.plugin-tools:maven-plugin-annotations:provided`
 - `org.apache.maven:maven-plugin-api:provided`
 - `org.apache.maven:maven-core`
 - `com.garganttua.core:garganttua-commons`
 - `com.garganttua.core:garganttua-reflection`
 - `com.garganttua.core:garganttua-reflections:provided`
 - `com.garganttua.core:garganttua-runtime-reflection:provided`
 - `javax.inject:javax.inject`

<!-- AUTO-GENERATED-END -->

## Core Concepts

### Goals

| Goal | Description | Default Phase |
|------|-------------|---------------|
| `prepare-script-jar` | Prepares manifest configuration for maven-jar-plugin | `prepare-package` |
| `script-jar` | Creates a complete JAR with Garganttua manifest attributes | `package` |

## Usage

### Basic Usage (Recommended)

Use the `script-jar` goal to create a self-contained JAR:

```xml
<plugin>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-script-maven-plugin</artifactId>
    <version>2.0.0-ALPHA05</version>
    <executions>
        <execution>
            <goals>
                <goal>script-jar</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### With Explicit Packages

Specify packages explicitly instead of auto-detection:

```xml
<plugin>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-script-maven-plugin</artifactId>
    <version>2.0.0-ALPHA05</version>
    <executions>
        <execution>
            <goals>
                <goal>script-jar</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <autoDetect>false</autoDetect>
        <packages>
            <package>com.myapp.expressions</package>
            <package>com.myapp.beans</package>
        </packages>
    </configuration>
</plugin>
```

### With Scan Packages (Limit Auto-Detection Scope)

```xml
<plugin>
    <groupId>com.garganttua.core</groupId>
    <artifactId>garganttua-script-maven-plugin</artifactId>
    <version>2.0.0-ALPHA05</version>
    <executions>
        <execution>
            <goals>
                <goal>script-jar</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <scanPackages>
            <scanPackage>com.myapp</scanPackage>
        </scanPackages>
    </configuration>
</plugin>
```

### Custom JAR Name

```xml
<configuration>
    <jarName>my-plugin.jar</jarName>
</configuration>
```

### Configuration Options

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `packages` | `List<String>` | - | Explicit list of packages to include in manifest |
| `autoDetect` | `boolean` | `true` | Whether to auto-detect packages with Garganttua annotations |
| `scanPackages` | `List<String>` | - | Base packages to scan for auto-detection |
| `jarName` | `String` | `${artifactId}-${version}-script.jar` | Name of the output JAR file |
| `includeResources` | `boolean` | `true` | Whether to include resources in the JAR |

### Generated Manifest

The plugin adds the following manifest attribute:

```
Garganttua-Packages: com.myapp.expressions,com.myapp.beans
```

### Detected Annotations

The plugin automatically detects packages containing classes/methods with these annotations:

### Expression Module
- `@Expression` - Expression functions for the script language

### Bootstrap Module
- `@Bootstrap` - Bootstrap builders

### Injection Module
- `@Prototype` - Prototype-scoped beans
- `@Property` - Property-injected fields
- `@ChildContext` - Child injection context factories
- `@Provider` - Bean providers
- `@Resolver` - Injectable element resolvers

### JSR-330 Standard (javax.inject)
- `@Singleton` - Singleton-scoped beans
- `@Inject` - Dependency injection points
- `@Qualifier` - Custom qualifiers
- `@Named` - Named beans

### Runtime Module
- `@RuntimeDefinition` - Workflow/runtime definitions
- `@Step` / `@Steps` - Runtime steps

### Mutex Module
- `@MutexFactory` - Mutex factory providers
- `@Mutex` - Mutex annotations

### Native Module
- `@NativeConfigurationBuilder` - GraalVM native configuration builders
- `@Native` - Native image hints

### DSL Module
- `@Scan` - Package scanning directives

### Mapper Module
- `@ObjectMappingRule` - Object mapping rules
- `@FieldMappingRule` - Field mapping rules

### Using in Scripts

Once your JAR is built with this plugin, include it in a Garganttua script:

```
# Load the plugin JAR
include("path/to/my-plugin.jar")

# Now you can use expressions defined in the JAR
myCustomExpression("argument")
```

### Example Project Structure

```
my-plugin/
├── pom.xml
└── src/
    └── main/
        └── java/
            └── com/
                └── myplugin/
                    └── expressions/
                        └── MyExpressions.java
```

**MyExpressions.java:**
```java
package com.myplugin.expressions;

import com.garganttua.core.expression.annotations.Expression;

public class MyExpressions {

    @Expression(name = "greet", description = "Greets someone")
    public static String greet(String name) {
        return "Hello, " + name + "!";
    }
}
```

**pom.xml:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.myplugin</groupId>
    <artifactId>my-plugin</artifactId>
    <version>1.0.0</version>

    <dependencies>
        <dependency>
            <groupId>com.garganttua.core</groupId>
            <artifactId>garganttua-commons</artifactId>
            <version>2.0.0-ALPHA05</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>com.garganttua.core</groupId>
                <artifactId>garganttua-script-maven-plugin</artifactId>
                <version>2.0.0-ALPHA05</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>script-jar</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

**Usage in script:**
```
include("target/my-plugin-1.0.0-script.jar")
greet("World")  # Output: Hello, World!
```

## Tips and best practices

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
Parameters this module declares or reads. Pass them with `-D` — on the JVM running the application for runtime scope, on the Maven command line for build scope.

| Parameter | Scope | Values | Default | Effect |
|---|---|---|---|---|
| `-Dgarganttua.packages=<pkg>[,<pkg>...]` | Runtime (JVM) | comma-separated package names | the package of the class passed to `GarganttuaApplication.run(...)` | Packages scanned for bootstrap auto-detection. Also set as a project property by `garganttua-script-maven-plugin` so a packaged script JAR carries its scan scope. |
| `-DjarName=<name>.jar` | Build (Maven plugin) | a file name | `${project.artifactId}-${project.version}-script.jar` | `garganttua-script-maven-plugin`: name of the executable script JAR produced. |
| `-Dpackages=<pkg>[,<pkg>...]` | Build (Maven plugin) | package names | none (auto-detection only) | Packages always written to the manifest / native config, on top of whatever auto-detection finds. |
| `-DautoDetect=<bool>` | Build (Maven plugin) | `true` \| `false` | `true` | `garganttua-script-maven-plugin`: scan for packages carrying Garganttua annotations instead of relying solely on `packages`. |
| `-DscanPackages=<pkg>[,<pkg>...]` | Build (Maven plugin) | package names | every package of the output directory | `garganttua-script-maven-plugin`: roots of the auto-detection scan. |
| `-DincludeResources=<bool>` | Build (Maven plugin) | `true` \| `false` | `true` | `garganttua-script-maven-plugin`: bundle the module's resources into the script JAR. |
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
