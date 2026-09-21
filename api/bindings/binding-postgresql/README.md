# Garganttua API PostgreSQL Binding

## Description

`garganttua-api-binding-postgresql` is a **dependency-only binding module** — it carries no Java sources. It pins the PostgreSQL JDBC driver (`org.postgresql:postgresql`) and the HikariCP connection pool (`com.zaxxer:HikariCP`) so that every consumer obtains exactly the same artifacts. Their versions live once in the api parent POM (`postgresql-driver.version`, `hikaricp.version`).

**Key Features:**
- **Single-source version pinning** — bump a version in the parent POM, every consumer follows
- **Zero application logic** — no classes, resources or configuration
- **Transitive re-export** — a module depending on this binding gets the driver and the pool on its compile classpath

## Installation

<!-- AUTO-GENERATED-START -->
<!-- AUTO-GENERATED-END -->

## Core Concepts

Consumed by `garganttua-api-dao-postgresql` (the driver) and `garganttua-api-starter-postgresql` (the pool).

## Usage

Depend on `garganttua-api-dao-postgresql` or the starter rather than on this binding directly.

## Tips and best practices

- Upgrade the driver or the pool by changing its version property in the api parent POM only.

## Parameters

<!-- AUTO-GENERATED-PARAMETERS-START -->
<!-- AUTO-GENERATED-PARAMETERS-END -->

## License
This module is distributed under the MIT License.
