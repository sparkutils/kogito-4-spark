# kogito-4-spark

A Kogito implementation of the [dmn-4-spark](https://github.com/sparkutils/dmn-4-spark) API.

The documentation site can be found [here](https://sparkutils.github.io/kogito-4-spark/).

## Supported Runtimes

Spark 3.5.x (Spark 4 hopefully coming soon) based runtimes on jdk 17 (OSS 2.13 builds are also provided).

Databricks requires the use of [JNAME](https://docs.databricks.com/aws/en/dev-tools/sdk-java#create-a-cluster-that-uses-jdk-17), with its associated reduction in support, in order to run on a non-jdk 8 VM for DBRs 14.0, 14.3 and 15.4.  16.x moves to JDK 17 by default.

## How to Use

Follow [these instructions](https://github.com/sparkutils/dmn-4-spark?tab=readme-ov-file#how-to-use) found on the API page to depend on the correct version.
