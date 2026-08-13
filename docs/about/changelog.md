## 0.1.0 Spark 4.1 DBR 18.3 <small>Xth August, 2026</small>

All Databricks EOS runtimes are removed. 

#19 - Support for Spark 4.1, Databricks 17.3 and 18.3 (Runtime 18) is added

#21 - Spark 4.2 and Connect support added

> [dmn-4-spark #9](https://github.com/sparkutils/dmn-4-spark/issues/9)
> 
> kogito-4-spark now provides two testshades, the original, as with 0.0.1 versions, provides the full set of
> functionality and the _connect version simply provides the test cases and the dmn-4-spark api.


[dmn-4-spark #12](https://github.com/sparkutils/dmn-4-spark/issues/12) - DMNExpressions move to non-deterministic, reducing overhead and increasing correctness for plans re-using projections 

## 0.0.1 Initial Version <small>Xth May, 2025</small>

Initial implementation of the dmn-4-spark api, providing:

* Arbitrary nested structure handling
* JSON input and output types
* Support for DDL DMNResult conversion including status output
* DMNInputFields can have non JSON type derived based on the fieldExpression
* Context input null handling is configurable
* WholestageCodegen support
* Kogito 10.2 support