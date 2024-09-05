# Trino JTOpen connector [![Actions Status](https://github.com/wAVeckx/trino-jtopen/workflows/Java%20CI/badge.svg)](https://github.com/wAVeckx/trino-jtopen/actions)

This is a connector for [Trino](https://trino.io/) that allows you to use IBM [JTOpen/JT400](https://github.com/IBM/JTOpen) Jdbc Connection to connect to a IBMi/Db2 for i/AS400 system.

What is Trino? 
Trino is a distributed SQL query engine designed to query large data sets distributed over one or more heterogeneous data sources.

**Notice**

Notice that this connector requires the connected database to be Db2 for IBMi and it requires the open source driver JTOpen/JT400. 
Writes have yet to be tested! 

See [DEVELOPMENT](DEVELOPMENT.md) for information on development process.


## Connection Configuration

Create new properties file like `<catalog-name>.properties` inside `/etc/trino/catalog` dir:

    connector.name=jtopen
    connection-url=jdbc:as400://ip:port/database;date format=iso;
    connection-user=myuser
    connection-password=mypassword

For a connection with SSL(Yet to be tested), use the following JDBC URL strings as `connection-url`:

    connection-url=jdbc:jtopen://ip:port/database:sslConnection=true;

**Notices**:
* The trailing semi-colon is required. Or it will throw SQLException `Invalid database URL syntax`.
* This connector was originally based on the code from [trino-db2](https://github.com/IBM/trino-db2) which is a connector for Db2 LUW using IBM connection/driver.
  
## Configuration Properties

| Property Name | Description |
|---------------|-------------|
|`jtopen.varchar-max-length` | max length of VARCHAR type in a CREATE TABLE or ALTER TABLE command. default is `32672`|
