# Trino Db2 for i connector (JTOpen) [![Actions Status](https://github.com/wAVeckx/trino-jtopen/workflows/Java%20CI/badge.svg)](https://github.com/wAVeckx/trino-jtopen/actions)

A [Trino](https://trino.io/) connector for **Db2 for i** on IBM i (formerly AS/400, iSeries), built on the
open source [JTOpen](https://github.com/IBM/JTOpen) JDBC driver (IBM Toolbox for Java). It lets Trino query and
write IBM i tables, join them with data from any other Trino catalog, and push filters, joins, aggregations and
limits down to Db2 for i where that is safe.

Releases are numbered after the Trino version they are built for: release 484 runs on Trino 484.

- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Querying Db2 for i](#querying-db2-for-i)
- [Type mapping](#type-mapping)
- [SQL support](#sql-support)
- [Performance](#performance)
- [IBM i specifics](#ibm-i-specifics)
- [Development](#development)

## Requirements

- Trino 484, running on Java 25, with the same connector release on every node.
- IBM i 7.3 or later. `BOOLEAN` columns require IBM i 7.5.
- Network access from every Trino node to the IBM i host servers used by JTOpen: the port mapper (449), the
  sign-on server (8476, or 9476 with TLS), the central server (8470 / 9470) and the database server (8471 / 9471).
- An IBM i user profile with authority to the libraries, tables and the `QSYS2` catalog views the queries use.

## Installation

Download `trino-jtopen-<version>.zip` from the [releases](https://github.com/wAVeckx/trino-jtopen/releases),
unpack it into the `plugin` directory of every Trino node so that the jars end up in `plugin/trino-jtopen/`, and
restart the nodes. The JTOpen driver is included.

## Configuration

Create a catalog properties file, for example `etc/catalog/ibmi.properties`:

```properties
connector.name=jtopen
connection-url=jdbc:as400://myibmi
connection-user=trino
connection-password=secret
```

The `connection-url` is a JTOpen URL: `jdbc:as400://host[:port][/default-schema][;property=value...]`. The optional
schema is the default schema for unqualified names in queries passed through with the `query` table function.
Any [JTOpen JDBC property](https://javadoc.io/doc/net.sf.jt400/jt400/latest/com/ibm/as400/access/doc-files/JDBCProperties.html)
can be appended. Properties in the URL take precedence over the defaults the connector sets, which are:

| Driver property       | Connector default | Why                                                                                   |
|-----------------------|-------------------|---------------------------------------------------------------------------------------|
| `block size`          | `512`             | Largest block of rows per fetch round trip. Trino reads whole result sets.            |
| `query optimize goal` | `2`               | `*ALLIO`: optimize for the entire result set. The driver default is `*FIRSTIO`.       |
| `use block update`    | `true`            | Blocked inserts for the batches Trino writes.                                         |
| `date format`         | `iso`             | Job date formats such as `*MDY` cannot represent years outside 1940 to 2039.          |
| `errors`              | `full`            | Second-level message text in errors.                                                  |
| `remarks`             | `sql`             | Table comments are the SQL comments set with `COMMENT ON`, not the object text.       |

Properties worth knowing about:

- `secure=true` enables TLS to the host servers, see [Connection security](#connection-security).
- `sort`, `sort language`, `sort weight` and `sort table` select the sort sequence of the connection, which decides
  how Db2 for i compares character data. See [Character columns and the sort sequence](#character-columns-and-the-sort-sequence)
  before changing them from the default `sort=hex`.
- `transaction isolation=none` is required to write to tables that are not journaled, see [Writes and journaling](#writes-and-journaling).
- `translate binary=true` reads columns with CCSID 65535 as character data, see [CCSID 65535 columns](#ccsid-65535-columns).
- `libraries` sets the library list of the server job, which only matters for unqualified names in `query` passthrough statements.
- `naming` must stay `sql`. The connector qualifies names as `"SCHEMA"."TABLE"`, which `naming=system` does not accept.

### Connection security

Set `secure=true` in the connection URL to use TLS to the IBM i host servers. The certificate of the IBM i system
must be trusted by the JVM running Trino, either through the JVM truststore or through the JTOpen properties
`tls truststore` and `tls truststore password`. Alternatively, use the Trino
[secrets](https://trino.io/docs/current/security/secrets.html) support to keep the password out of the catalog file.

### Connector properties

| Property                                           | Default | Description                                                                                                                                                                                                                                                                                                                            |
|----------------------------------------------------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jtopen.case-insensitive-sort-sequence`            | `false` | Set to `true` when the `sort` properties of the connection URL select a shared-weight sort sequence (`sort=language` with the default `sort weight=shared`, or a shared-weight sort table). Db2 for i then compares character data without regard to case, and the connector keeps all character comparisons and `LIKE` in Trino. The connector refuses to start when the property contradicts the `sort` properties of the URL; with `sort=job` it cannot see the sort sequence of the user profile and trusts the property. |
| `jtopen.varchar-comparison-semantics`              | `EXACT` | How `VARCHAR` comparisons are pushed down. `EXACT` keeps Trino semantics, where trailing blanks are significant, by sending each equality together with a character length check. `PAD_SPACE` accepts Db2 for i semantics, where values that differ only in trailing blanks are equal, and sends comparisons as they are. Can be overridden per session with the `varchar_comparison_semantics` session property. |
| `jtopen.varchar-max-length`                        | `32739` | Length of the `VARCHAR` column created for an unbounded Trino `varchar`. Longer bounded `varchar(n)` types are created as `CLOB(n)`.                                                                                                                                                                                               |
| `jtopen.stored-procedure-table-function-enabled`   | `false` | Enables the `procedure` table function, see [Table functions](#table-functions).                                                                                                                                                                                                                                                     |

### General configuration properties

The connector supports the configuration properties common to all Trino JDBC connectors, among them:

| Property                                  | Default  | Description                                                                                         |
|-------------------------------------------|----------|-----------------------------------------------------------------------------------------------------|
| `case-insensitive-name-matching`          | `false`  | Match schema and table names case-insensitively, see the Trino [JDBC connector documentation](https://trino.io/docs/current/connector/sqlserver.html#case-insensitive-matching). |
| `metadata.cache-ttl`                      | `0s`     | How long table and column metadata is cached. Set it to, for example, `1m` on busy systems.          |
| `metadata.cache-missing`                  | `false`  | Cache the fact that a table does not exist.                                                         |
| `statistics.enabled`                      | `true`   | Use Db2 for i table statistics for cost-based optimization, see [Table statistics](#table-statistics). |
| `write.batch-size`                        | `1000`   | Rows per batched `INSERT`.                                                                          |
| `insert.non-transactional-insert.enabled` | `false`  | Write directly into the target table instead of staging rows in a temporary table.                  |
| `join-pushdown.enabled`                   | `true`   | Push joins between tables of the same catalog down to Db2 for i.                                    |
| `aggregation-pushdown.enabled`            | `true`   | Push aggregations down to Db2 for i.                                                                |
| `topn-pushdown.enabled`                   | `true`   | Push `ORDER BY ... LIMIT` down to Db2 for i.                                                        |
| `domain-compaction-threshold`             | `256`    | Maximum number of values in a pushed down `IN` list before it is simplified to a range.            |
| `dynamic-filtering.enabled`               | `true`   | Push dynamic filters from joins down to Db2 for i.                                                  |
| `unsupported-type-handling`               | `IGNORE` | `CONVERT_TO_VARCHAR` exposes columns of unsupported types, such as `DECFLOAT`, as `varchar`.        |
| `jdbc-types-mapped-to-varchar`            |          | Comma-separated list of Db2 for i type names to expose as `varchar`, for example `DECFLOAT,ROWID`.   |
| `query.comment-format`                    |          | Comment appended to every query sent to Db2 for i, for example `Trino query $QUERY_ID`.               |

The full list is in the Trino documentation of any JDBC connector, such as the
[SQL Server connector](https://trino.io/docs/current/connector/sqlserver.html#general-configuration-properties).

## Querying Db2 for i

A Db2 for i schema is an IBM i library. Trino lowercases identifiers, so library `SALES` and table `ORDERS` are
queried as `ibmi.sales.orders`. Names longer than 10 characters are the SQL long names of the objects; the connector
never uses the 10-character system names.

```sql
SHOW SCHEMAS FROM ibmi;
SHOW TABLES FROM ibmi.sales;
SELECT * FROM ibmi.sales.orders WHERE orderdate >= DATE '2024-01-01';
```

Every library the user profile can access is listed, including `QSYS2` and `SYSIBM`, so the IBM i Services views
are available directly, for example `SELECT * FROM ibmi.qsys2.active_job_info`.

## Type mapping

Because Trino and Db2 for i each support types the other does not, the connector maps types in both directions.

### Db2 for i type to Trino type mapping

| Db2 for i type                                   | Trino type         | Notes                                                                                     |
|--------------------------------------------------|--------------------|-------------------------------------------------------------------------------------------|
| `BOOLEAN`                                        | `boolean`          | IBM i 7.5                                                                                 |
| `SMALLINT`                                       | `smallint`         |                                                                                           |
| `INTEGER`                                        | `integer`          |                                                                                           |
| `BIGINT`                                         | `bigint`           |                                                                                           |
| `REAL`, `FLOAT(n)` with n up to 24               | `real`             |                                                                                           |
| `DOUBLE`, `FLOAT`                                | `double`           |                                                                                           |
| `DECIMAL(p, s)`, `NUMERIC(p, s)`                 | `decimal(p, s)`    | Precision above 38 follows the `decimal-mapping` and `decimal-rounding-mode` properties.   |
| `CHAR(n)`, `GRAPHIC(n)`, `NCHAR(n)`              | `char(n)`          |                                                                                           |
| `VARCHAR(n)`, `VARGRAPHIC(n)`, `NVARCHAR(n)`     | `varchar(n)`       |                                                                                           |
| `CLOB`, `DBCLOB`, `NCLOB`, `XML`                 | `varchar`          | No predicate pushdown.                                                                    |
| `CHAR FOR BIT DATA`, `BINARY`, `VARBINARY`, `BLOB` | `varbinary`      | Columns with CCSID 65535 are reported as binary, see [CCSID 65535 columns](#ccsid-65535-columns). |
| `DATE`                                           | `date`             |                                                                                           |
| `TIME`                                           | `time(0)`          | Db2 for i `TIME` has no fractional seconds.                                               |
| `TIMESTAMP(p)`                                   | `timestamp(p)`     | p from 0 to 12                                                                            |

`DECFLOAT`, `ROWID`, `DATALINK` and `TIMESTAMP WITH TIME ZONE` columns are not mapped and are hidden, unless
`unsupported-type-handling` or `jdbc-types-mapped-to-varchar` expose them as `varchar`.

### Trino type to Db2 for i type mapping

| Trino type                         | Db2 for i type                     | Notes                                                                                |
|------------------------------------|------------------------------------|--------------------------------------------------------------------------------------|
| `boolean`                          | `BOOLEAN`                          | IBM i 7.5                                                                            |
| `tinyint`, `smallint`              | `SMALLINT`                         | Db2 for i has no `TINYINT`.                                                          |
| `integer`                          | `INTEGER`                          |                                                                                      |
| `bigint`                           | `BIGINT`                           |                                                                                      |
| `real`                             | `REAL`                             |                                                                                      |
| `double`                           | `DOUBLE PRECISION`                 |                                                                                      |
| `decimal(p, s)`                    | `DECIMAL(p, s)`                    |                                                                                      |
| `char(n)`                          | `CHAR(n)`                          | `CLOB(n)` above 32766.                                                               |
| `varchar(n)`                       | `VARCHAR(n)`                       | `CLOB(n)` above `jtopen.varchar-max-length`.                                         |
| `varchar`                          | `VARCHAR(32739)`                   | Length set by `jtopen.varchar-max-length`.                                           |
| `varbinary`                        | `BLOB(2G)`                         | Trino `varbinary` is unbounded; Db2 for i `VARBINARY` holds at most 32739 bytes.      |
| `date`                             | `DATE`                             |                                                                                      |
| `time(p)`                          | `TIME`                             | Stored as `time(0)`; fractional seconds are not kept.                                |
| `timestamp(p)`                     | `TIMESTAMP(p)`                     | p from 0 to 12                                                                       |

Other types are not supported in `CREATE TABLE` and `INSERT`.

## SQL support

The connector supports reading and writing data and metadata:

- `SELECT`, including the pushdown described under [Performance](#performance)
- `INSERT`, `UPDATE`, `DELETE`, `MERGE` and `TRUNCATE`
- `CREATE TABLE`, `CREATE TABLE AS`, `DROP TABLE` and `RENAME TABLE` within a schema
- `ALTER TABLE ... ADD COLUMN`, `DROP COLUMN`, `SET DATA TYPE` and `DROP NOT NULL`
- `COMMENT ON TABLE` and `COMMENT ON COLUMN`, including the table comment of `CREATE TABLE`
- `CREATE SCHEMA` and `DROP SCHEMA`, which create and delete libraries

Not supported: renaming columns or schemas, moving a table to another schema, views, and column comments in
`CREATE TABLE`.

`UPDATE` and `DELETE` run as a single statement on Db2 for i when the whole `WHERE` clause can be pushed down.
Otherwise, and for `MERGE`, Trino changes rows one by one through the primary key of the table, so the table must
have a primary key constraint. A keyed physical file without a primary key constraint does not qualify.

### Writes and journaling

Trino writes under commitment control. Db2 for i only allows that for journaled tables, which includes every table
in a schema created with `CREATE SCHEMA` or by Trino. Writing to a table in a library created with `CRTLIB` that is
not journaled fails with `SQL7008`. Either start journaling the table, or add `transaction isolation=none` to the
connection URL, which writes every batch immediately and leaves nothing to roll back if a query fails.

### Procedures

- `CALL ibmi.system.flush_metadata_cache()` clears the cached metadata.
- `CALL ibmi.system.execute('<statement>')` runs a statement, such as DDL, directly on Db2 for i.

### Table functions

- `SELECT * FROM TABLE(ibmi.system.query(query => '<SELECT statement>'))` runs a query as written on Db2 for i and
  returns its result. The statement is not validated by Trino, and unqualified names resolve against the default
  schema of the connection.
- `SELECT * FROM TABLE(ibmi.system.procedure(query => 'CALL <procedure>'))` returns the result set of a stored
  procedure, when `jtopen.stored-procedure-table-function-enabled=true`. Db2 for i cannot describe the result set of
  a procedure without running it, so the procedure runs once to describe the result and again to read it. Do not use
  it with procedures that have side effects.

## Performance

### Table statistics

The cost-based optimizer uses the row count of every table and the distinct value count, null count and average
length of columns for which Db2 for i has collected statistics, read from `QSYS2.SYSTABLESTAT` and
`QSYS2.SYSCOLUMNSTAT`. Db2 for i collects column statistics automatically as queries need them.

### Pushdown

The connector pushes the following down to Db2 for i, so that filtering and aggregation happen on IBM i and only the
result travels to Trino:

- predicates, including dynamic filters from joins
- joins between tables of the same catalog
- `count`, `count(DISTINCT)` on numeric columns, `sum`, `avg`, `min`, `max`, `stddev`, `stddev_pop`, `stddev_samp`,
  `variance`, `var_pop`, `var_samp`, `covar_pop`, `covar_samp`, `corr`, `regr_intercept` and `regr_slope`
- `LIMIT`, and `ORDER BY ... LIMIT` on non-character columns

A pushed down operation only happens when its result is the same as Trino's. Db2 for i differs from Trino in how it
compares character data, which restricts pushdown on `CHAR` and `VARCHAR` columns as described next.

### Character columns and the sort sequence

Db2 for i compares and orders character strings according to the **sort sequence** of the connection, selected
with the `sort` JDBC properties, and with **PAD SPACE** semantics: the shorter operand is padded with blanks, so
`'a' = 'a '` is true. Trino compares `varchar` byte by byte with NO PAD, is always case-sensitive, and orders by
Unicode code point. Three consequences follow:

- Under the driver default `sort=hex`, comparisons are case-sensitive and ordering follows the bytes of the column
  CCSID, which for EBCDIC columns puts lowercase before uppercase before digits. Range predicates and `ORDER BY` on
  character columns can therefore not be pushed down.
- Under a shared-weight sort sequence (`sort=language` with the default `sort weight=shared`), comparisons also
  ignore case. Tell the connector with `jtopen.case-insensitive-sort-sequence=true`. The connector compares the
  property with the `sort` properties of the URL at startup and refuses to start when they contradict each other.
  `sort=job` takes the sort sequence of the user profile, which the connector cannot see, so there the property is
  trusted.
- `CHAR` values are compared the same way in both systems, because Trino ignores trailing blanks in `char` values
  too. `VARCHAR` equality is wider on Db2 for i than in Trino whenever values differ only in trailing blanks.

For `VARCHAR` columns the connector closes that last gap the way the PostgreSQL connector uses `COLLATE "C"`. Under
`sort=hex`, a padded equality together with equal character counts is an exact equality, so `WHERE v = 'a'` is sent
as

```sql
WHERE v = ? AND CHARACTER_LENGTH(v) = CHARACTER_LENGTH(CAST(? AS VARCHAR(32739)))
```

Db2 for i still uses an index on `v`, and it counts the characters of both the column and the parameter itself, so
the check is exact whatever the CCSID of the column: EBCDIC such as 37, UTF-8 (1208) or UTF-16 (1200, `NVARCHAR`).
`<>`, `IN`, `NOT IN`, `NULLIF` and join conditions are rewritten the same way. Columns the driver reports as
`VARGRAPHIC` or `LONG VARCHAR`, for which no safe parameter cast exists, are pushed down as a pre-filter instead.

What is pushed down on character columns:

| Predicate                                          | `CHAR`, `sort=hex` | `VARCHAR`, `sort=hex`, `EXACT` | `VARCHAR`, `PAD_SPACE` | Case-insensitive sort sequence |
|----------------------------------------------------|--------------------|--------------------------------|------------------------|--------------------------------|
| `IS NULL`, `IS NOT NULL`                           | fully              | fully                          | fully                  | fully                          |
| `= literal`, `IN (literals)`                       | fully              | fully, with the length check   | fully                  | as a pre-filter                |
| `<> literal`, `NOT IN (literals)`                  | fully              | fully, with the length check   | fully                  | in Trino                       |
| `=`, `<>`, `IN`, `NULLIF` between columns, join `=` | fully             | fully, with the length check   | fully                  | in Trino                       |
| `LIKE`                                             | fully              | fully                          | fully                  | in Trino                       |
| `<`, `>`, `BETWEEN`                                | in Trino           | in Trino                       | in Trino               | in Trino                       |
| `ORDER BY ... LIMIT`, `GROUP BY`, `min`, `max`, `count(DISTINCT)` | in Trino | in Trino                    | in Trino               | in Trino                       |

"As a pre-filter" means the predicate is sent to Db2 for i, which returns a superset of the matching rows, and Trino
applies the exact predicate again. The rows transferred are the same as with a full pushdown, but the remaining
filter keeps the aggregations, joins and limits above it from being pushed down.

`jtopen.varchar-comparison-semantics=PAD_SPACE`, or `SET SESSION ibmi.varchar_comparison_semantics = 'PAD_SPACE'`
for a single session, drops the length check and sends `VARCHAR` comparisons as they are. Results then follow
Db2 for i: `status = 'OPEN'` also matches `'OPEN '`, as it would in any IBM i application. Use it when that is the
behavior you want, or for `VARGRAPHIC` columns, which the length check does not cover. It is not needed for
performance.

Keep the default `sort=hex` unless the sort sequence is required for other reasons; a language sort sequence makes
fewer predicates pushable and can prevent the use of indexes built with a different sort sequence.

## IBM i specifics

### CCSID 65535 columns

Character columns with CCSID 65535 (`*HEX`), common in applications migrated from older releases, carry no
character set and are reported by the driver as binary data, so they appear as `varbinary` in Trino. Add
`translate binary=true` to the connection URL to read them as character data in the CCSID of the server job.

### Date formats

Dates are exchanged as values, not as text, so the job date format does not affect results. The connector sets
`date format=iso` for the statements it generates. Statements passed through with the `query` table function must
use ISO date literals, such as `DATE '2024-01-15'`.

### Identifiers

Trino sends identifiers as it stores them: lowercase names are looked up as uppercase, which is how Db2 for i
stores unquoted names. Tables or columns created on IBM i with mixed-case delimited names are reachable with
`case-insensitive-name-matching=true`.

### Reads, isolation and locks

Reading never takes row locks. Trino reads with auto-commit on, and the JTOpen driver runs auto-commit
statements under isolation `*NONE` unless `true autocommit=true` is set. Even under the driver's default
`transaction isolation=read uncommitted`, which is `*CHG`, Db2 for i takes no row locks for read-only fetches.
Readers do not wait for rows that other jobs are changing either; they see the uncommitted data, as any `*CHG`
reader on IBM i does. Only writes run under commitment control, see [Writes and journaling](#writes-and-journaling).

What a running query does hold is the `*SHRRD` object lock that Db2 for i places on a file while a cursor is
open on it. It does not affect other readers or writers, but a job that needs the file exclusively, such as
`CLRPFM`, `RGZPFM`, a restore or some `ALTER TABLE` operations, waits until the Trino scan finishes. Keep that
in mind when scheduling such jobs, and use Trino's `query.max-execution-time` to bound long queries.

For a catalog that only reads:

- Add `access=read only` to the connection URL. The driver then rejects every statement other than `SELECT`, so
  nothing can be written through the catalog even by mistake. Trino's file-based access control can declare the
  catalog `read-only` as well.
- If readers must see committed data only, use
  `transaction isolation=read committed;true autocommit=true;concurrent access resolution=1` in the URL. Reads
  then run under `*CS` with "currently committed" semantics: a reader gets the last committed version of a row
  another job is changing instead of waiting for it. `*CS` holds a read lock on the current row of the cursor
  until the next fetch, which is short-lived.
- Do not use `repeatable read` or `serializable`. They hold read locks until the transaction ends, and Trino
  scans can run long.

### Authorities

Reading table statistics and comments uses the `QSYS2.SYSTABLESTAT`, `QSYS2.SYSCOLUMNSTAT` and `QSYS2.SYSSCHEMAS`
catalog views, which are readable by default. Writes need `*CHANGE` authority to the table and, for `CREATE TABLE`,
`*CHANGE` to the library.

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for building, running the unit tests, running the Trino connector test suite
against an IBM i system, and releasing.

## License

[Apache License 2.0](LICENSE)
