---
tags:
- basic
- getting started
- beginner
---

The aim is to have explicit support for LTS', other interim versions may be supported as needed.

## Running on Databricks Runtime 16.4

Databricks supports both 2.12 and 2.13 scala versions for 16.4, ensure the correct runtime is used.

## Running on Databricks Runtime 17+

Databricks 17+ supports the base Spark 4 dataset api, allowing both Connect and Classic usage from the same code base.

The following test combinations are supported as of 0.1.0:

| Compute Type   | Cluster Library                            | Extension                           | Connect Via dmn-4-spark api | SPARKUTILS_DISABLE_CLASSIC_TESTS (default false) | SPARKUTILS_DISABLE_CONNECT_TESTS (default false) | 
|----------------|--------------------------------------------|-------------------------------------|-----------------------------|--------------------------------------------------|--------------------------------------------------|
| Non Shared     | kogito-4-spark_testshade_18.3.dbr          |                                     |                             |                                                  | true                                             |
| Non Shared     | kogito-4-spark_testshade_18.3.dbr          | kogito-4-spark_testshade_18.3.dbr   | :octicons-checkbox-24:      |                                                  |                                                  |
| Shared Compute | kogito-4-spark_connect_testshade_18.3.dbr  | kogito-4-spark_testshade_18.3.dbr   | :octicons-checkbox-24:      | true                                             |                                                  |
| Shared Compute | kogito-4-spark_connect_testshade_4.1.0.oss | kogito-4-spark_testshade_18.3.dbr   | :octicons-checkbox-24:      | true                                             |                                                  |
| Shared Compute | kogito-4-spark_api_18.3.dbr                | kogito-4-spark_18.3.dbr             | :octicons-checkbox-24:      |                                                  |                                                  |
| Shared Compute | kogito-4-spark_api_4.1.0.oss               | kogito-4-spark_18.3.dbr             | :octicons-checkbox-24:      |                                                  |                                                  |

Databricks 18 has been tested as of release__18.3.x-snapshot-photon-scala2.13__databricks__18.3.3__bfd1bbf__b0356d3__jenkins__bc102bd__format-3 (search for spark.databricks.clusterUsageTags.sparkImageLabel on the environment spark properties page for the exact version your cluster uses)

## Testing out kogito-4-spark via Notebooks

You can use the appropriate runtime kogito-4-spark_testshade artefact jar (e.g. [DBR 18.3](https://s01.oss.sonatype.org/content/repositories/releases/com/sparkutils/kogito-4-spark_testshade_18.3.dbr_4.1_2.13/0.1.0-baseline/kogito-4-spark_testshade_18.3.dbr_4.1_2.13-0.1.0-baseline.jar)) from maven to upload into your workspace / notebook env (or add via maven).  When using Databricks make sure to use the appropriate _Version.dbr builds.

Then using:

```scala
import com.sparkutils.dmn.kogito.DMNTestRunner
import com.sparkutils.testing.SparkTestUtils

// uncomment to disable connect test usage on runtimes that support it, like DBR 17.3
// System.setProperty("SPARKUTILS_DISABLE_CONNECT_TESTS","true")

// uncomment to disable classic test usage on runtimes that support connect, DBR 17.3
// a good use case is simulating a UC shared cluster (with init script / spark session extensions enabled) 
// when running on a classic cluster setup.
// On an actual UC Shared Compute cluster this is true by default (as the connect.SparkSession is provided) 
// System.setProperty("SPARKUTILS_DISABLE_CLASSIC_TESTS","true")

// for running on azure set the configuration for both classic and connect client
val keyMap = Map(s"fs.azure.account.key.${srv_path}${dfs}" -> accountKey)
SparkTestUtils.setRuntimeConnectClientConfig(keyMap)
SparkTestUtils.setRuntimeClassicConfig(keyMap)

val root_path = loc
SparkTestUtils.setPath(root_path+"/kogito")
DMNTestRunner.test()
```

in your cell will run through all the test suite used when building kogito-4-spark.

Ideally, at the end of your runs you'll see - after 2 minutes or so and some stdout - for example a run on DBR 18.3 provides:

```
Run completed in 1 minute, 41 seconds.
Total number of tests run: 79
Suites: completed 5, aborted 0
Tests: succeeded 79, failed 0, canceled 0, ignored 0, pending 0
All tests passed.
Kogito4Spark - gc'ing after finishing test batch 0
all Kogito4Spark test batches completed
```

The exact number of tests run depends on the test setup and connect usage (82 are expected for standard classic clusters).

## Spark Connect Session Extensions

Starting with Spark 4 and dmn-4-spark 0.1.0 the api is now a separate jar that can be used remotely over Spark 4 Connect.

In order to run against remote clusters the configuration option:

```
spark.sql.extensions=com.sparkutils.dmn.DMN4SparkExtension
```

must be used.  In Databricks this also requires using an init script to copy the implementation jars e.g.:

```bash
#!/bin/bash

cp /Volumes/databricks_ws/default/jars/kogito-4-spark_testshade_4.1.0.oss_4.1_2.13-0.1.0.jar /databricks/jars/kogito-4-spark_testshade_4.1.0.oss_4.1_2.13-0.1.0.jar
```

When using this approach on a 'standard' shared Databricks cluster the cluster jar must be the _connect versions, based on the dmn-4-spark_api only and not the backend code.
