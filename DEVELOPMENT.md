 # Development

 Developers should follow the [development guidelines](https://github.com/trinodb/trino/blob/81e9233eae31f2f3b425aa63a9daee8a00bc8344/DEVELOPMENT.md)
 from the Trino community.

## Build

    mvn clean install

## Release
First update the `main` branch of this repo via PR process. Then, go to https://github.com/wAVeckx/trino-jtopen/releases to draft your release. Configure the release to create a new branch named after the Trino version (e.g. 406). Before publishing the release, build the plugin locally with `mvn clean install`, and upload the resulting archive `target/trino-jtopen-[version].zip` to the release binaries. Then, you may click "publish release".


## Docker is yet to be implemented and tested. 
