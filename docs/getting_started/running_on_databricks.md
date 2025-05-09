---
tags:
- basic
- getting started
- beginner
---

The aim is to have explicit support for LTS', other interim versions may be supported as needed.

## Running on Databricks Runtime 16.4

Databricks supports both 2.12 and 2.13 scala versions for 16.4, ensure the correct runtime is used.

## Testing out kogito-4-spark via Notebooks

You can use the appropriate runtime kogito-4-spark_testshade artefact jar (e.g. [DBR 16.4](https://s01.oss.sonatype.org/content/repositories/releases/com/sparkutils/kogito-4-spark_testshade_16.3.dbr_3.5_2.12/0.0.1-RC14/kogito-4-spark_testshade_16.3.dbr_3.5_2.12-0.0.1-RC14.jar)) from maven to upload into your workspace / notebook env (or add via maven).  When using Databricks make sure to use the appropriate _Version.dbr builds.

Then using:

```scala
import com.sparkutils.dmn.kogito.tests.TestSuite
import com.sparkutils.dmn.kogito.TestUtils

TestUtils.setPath("path_where_test_files_should_be_generated")
TestSuite.runTests()
```

in your cell will run through all of the test suite used when building kogito-4-spark.

In Databricks notebooks you can set the path up via:

```scala
val fileLoc = s"/Workspace/Users/${dbutils.notebook.getContext.userName.getOrElse("youridgoeshere")}/kogito-4-spark-testdir"
TestUtils.setPath(fileLoc)
```

Ideally at the end of your runs you'll see - after 2 minutes or so and some stdout - for example a run on DBR 16.4 provides:

```
.......................................................
Time: 85.331

OK (55 tests)

Finished. Result: Failures: 0. Ignored: 0. Tests run: 55. Time: 85331ms.
import com.sparkutils.dmn.kogito.tests.TestSuite
import com.sparkutils.dmn.kogito.TestUtils
fileLoc: String = /Workspace/Users/name@domain/kogito-4-spark-testdir
```
