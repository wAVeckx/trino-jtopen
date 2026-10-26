# Development

The connector follows the [development guidelines](https://github.com/trinodb/trino/blob/master/DEVELOPMENT.md)
of the Trino project: the same code style, checkstyle rules and test framework. It is built with the Trino
plugin tooling (`trino-maven-plugin`) on top of `trino-base-jdbc`, so most of the behavior comes from the
Trino JDBC connector framework and the code here is the Db2 for i specific part.

## Building

Java 25 and network access to Maven Central are required. The Maven wrapper picks the right Maven version.

```shell
./mvnw clean package
```

This compiles, runs checkstyle, the dependency and SPI checks, the unit tests, and produces the plugin archive
`target/trino-jtopen-<version>.zip`.

## Unit tests

The unit tests run without an IBM i system and cover the configuration, the connector wiring and the pushdown
rules for character columns:

```shell
./mvnw test -Dtest='TestJTOpenConfig,TestJTOpenPlugin,TestJTOpenClient,TestJTOpenQueryBuilder,TestJTOpenSortSequence'
```

## Integration tests against an IBM i system

`TestJTOpenConnectorTest` runs the Trino connector test suite (`BaseJdbcConnectorTest`, several hundred tests)
and `TestJTOpenTypeMapping` round trips values of every mapped type in both directions. They need a live IBM i
system and are skipped unless these environment variables are set:

| Variable                 | Description                                                                            |
|--------------------------|----------------------------------------------------------------------------------------|
| `JTOPEN_TEST_JDBC_URL`   | JTOpen URL of the system, for example `jdbc:as400://myibmi`                            |
| `JTOPEN_TEST_USER`       | User profile that may create schemas and tables                                        |
| `JTOPEN_TEST_PASSWORD`   | Its password                                                                           |
| `JTOPEN_TEST_SCHEMA`     | Schema (library) the tests use, created when missing. Default `TRINOTEST`              |

```shell
export JTOPEN_TEST_JDBC_URL=jdbc:as400://myibmi
export JTOPEN_TEST_USER=trino
export JTOPEN_TEST_PASSWORD=secret
./mvnw test -Dtest='TestJTOpenConnectorTest,TestJTOpenTypeMapping'
```

The suite copies the TPC-H `tiny` tables into the test schema on first use and creates and drops many small
tables, so point it at a system you may do that on. Behaviors the connector does not support are listed in
`TestJTOpenConnectorTest.hasBehavior`; a test that fails because Db2 for i behaves differently from what the
suite expects is adjusted there or with the `verify...Permissible` and `errorMessageFor...` hooks, the way the
SQL Server and Oracle connectors in Trino do it.

`JTOpenQueryRunner.main` starts a single-node Trino on port 8080 with an `jtopen` catalog pointing at the same
system, for trying queries by hand with the Trino CLI.

## Updating to a new Trino release

Each Trino release gets a connector release of the same number. Update `pom.xml` to the versions Trino pins in
its own `pom.xml` for that release, including the `airbase` parent, `dep.airlift.version`,
`dep.airlift.guice.version` (from the airbase pom), `dep.jdbi.version`, `provisio-maven-plugin` and
`trino-maven-plugin`, and keep `.mvn/wrapper/maven-wrapper.properties` on the Maven version Trino uses. Then
build, read the Trino release notes for SPI and `trino-base-jdbc` changes, and run the unit tests. Run the
integration tests against an IBM i system before publishing the release.

## Release

Merge the changes to `main` through a pull request, then draft a release at
https://github.com/wAVeckx/trino-jtopen/releases, with a tag named after the Trino version (for example `484`).
The CI build attaches `target/trino-jtopen-<version>.zip` to the release when the tag is pushed; alternatively,
build it locally with `./mvnw clean package` and upload it by hand.
