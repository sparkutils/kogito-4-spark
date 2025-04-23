# kogito-4-spark

A Kogito implementation of the dmn-4-spark API.

## Supported Runtimes

Spark 3.5.x (Spark 4 hopefully coming soon) based runtimes on jdk 17 (OSS 2.13 builds are also provided).

Databricks requires the use of [JNAME](https://docs.databricks.com/aws/en/dev-tools/sdk-java#create-a-cluster-that-uses-jdk-17), with its associated reduction in support, in order to run on a non-jdk 8 VM for DBRs 14.0, 14.3 and 15.4.  16.x moves to JDK 17 by default.

## Supported DMNContextProviders

The following types are supported and provided to the org.kie.dmn.api.core.DMNContext 

* JSON - string json representation
* StringType
* IntegerType
* LongType
* BooleanType
* DoubleType
* FloatType
* BinaryType - provided as a byte[]
* ByteType
* ShortType
* DateType - provided as a LocalDate
* TimestampType - provided as a LocalDateTime

All other DDL types (decimal, struct, array etc.) are currently unsupported.

Non DDL Unary DMNContextProviders may be provided via a fully qualified class name and must provide a two arg constructor of DMNContextPath, Expression. 
 
## Supported DMNResultProviders

* JSON - Serializes the org.kie.dmn.api.core.DMNResult.getDecisionResults
* ARRAY<BOOLEAN> - Serializes the first result when the type is an array of Booleans (used by tests, definitely not general)

Other DMNResultProviders may be provided via a fully qualified class name. 
 