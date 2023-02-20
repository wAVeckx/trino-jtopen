# Trino JTOpen connector [![Actions Status](https://github.com/wAVeckx/trino-jtopen/workflows/Java%20CI/badge.svg)](https://github.com/wAVeckx/trino-jtopen/actions)

This is a connector for [Trino](https://trino.io/) that allows you to use IBM [JTOpen/JT400](https://jt400.sourceforge.net) Jdbc Connection.
This connector was originally based on the code from [trino-db2](https://github.com/IBM/trino-db2) which is a connector for Db2 LUW IBM connection. 

What is Trino? 
Trino is a distributed SQL query engine designed to query large data sets distributed over one or more heterogeneous data sources.

Notice that this connector requires the connected database to be Db2 for IBMi and it requires the open source driver JTOpen/JT400. 

See [DEVELOPMENT](DEVELOPMENT.md) for information on development process.


## Connection Configuration

Create new properties file like `<catalog-name>.properties` inside `etc/catalog` dir:

    connector.name=db2
    connection-url=jdbc:db2://ip:port/database
    connection-user=myuser
    connection-password=mypassword

For a connection with SSL(Yet to be tested), use the following JDBC URL strings as `connection-url`:

    connection-url=jdbc:db2://ip:port/database:sslConnection=true;

**Notices**:
* the trailing semi-colon is required. Or it will throw SQLException `Invalid database URL syntax`.

## Configuration Properties

| Property Name | Description |
|---------------|-------------|
|`db2.varchar-max-length` | max length of VARCHAR type in a CREATE TABLE or ALTER TABLE command. default is `32672`|


