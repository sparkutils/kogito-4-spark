---
tags:
- basic
- getting started
- beginner
---

The aim is to have explicit support for LTS', other interim versions may be supported as needed.

## Running on Databricks Runtime 16.3



## Testing out kogito-4-spark via Notebooks

You can use the appropriate runtime kogito-4-spark_testshade artefact jar (e.g. [DBR 11.3](https://s01.oss.sonatype.org/content/repositories/releases/com/sparkutils/quality_testshade_11.3.dbr_3.3_2.12/)) from maven to upload into your workspace / notebook env (or add via maven).  When using Databricks make sure to use the appropriate _Version.dbr builds.

Then using:

```scala
import com.sparkutils.dmn.kogito.tests.TestSuite
import com.sparkutils.dmn.kogito.TestUtils

TestUtils.setPath("path_where_test_files_should_be_generated")
TestSuite.runTests
```

in your cell will run through all of the test suite used when building kogito-4-spark.

In Databricks notebooks you can set the path up via:

```scala
val fileLoc = "/dbfs/databricks/kogito_test"
TestUtils.setPath(fileLoc)
```

Ideally at the end of your runs you'll see - after 10 minutes or so and some stdout - for example a run on DBR 14.3 provides:

```
Time: 63.686

OK (34 tests)

Finished. Result: Failures: 0. Ignored: 0. Tests run: 34. Time: 633686ms.
import com.sparkutils.quality.tests.TestSuite
import com.sparkutils.qualityTests.SparkTestUtils
fileLoc: String = /dbfs/databricks/quality_test
```
