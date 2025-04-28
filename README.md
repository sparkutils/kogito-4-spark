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
* Struct<...> DDL - with each field representing a decision name to result mapping

Other DMNResultProviders may be provided via a fully qualified class name. 
 
When Struct DDL is used each decisionName in the Kogito DMNResult will be stored against that struct, e.g. for a decision name "evaluate" which returns a list of booleans the DDL:

```ddl
struct<evaluate: array<boolean>>
```

should be used.  Where the decisionName is not present in the results null is used, each element will therefore be set to nullable by the library.  Where a decision result is provided which is not the in the DDL it will be ignored (debug information may however be provided).

Use JSON to handle result schema evolution until a possible solution via Variants in Spark 4 is investigated. 

### Result Processing

In order to identify if a null result is due to an error or not a "_dmnEvalStatus: Byte" field can be added to the Struct DDL, e.g.:

```ddl
struct<evaluate: array<boolean>, evaluate_dmnEvalStatus: Byte>
```

will store the Kogito DMNDecisionResult.getEvaluationStatus as a Byte with the following values:

| DecisionEvaluationStatus (Severity)              | _dmnEvalStatus Int stored                |
|--------------------------------------------------|------------------------------------------|
| NOT_FOUND (kogito-4-spark only[^1])                  | -6 (Typically a sign of a name mismatch) |
| NOT_EVALUATED                                    | -5 (Should not happen)                   |
| EVALUATING                                       | -4 (Should not happen)                   |
| SUCCEEDED                                        | 1                                        |
| SKIPPED (WARN Msg.MISSING_EXPRESSION_FOR_DECISION) | -3                                       |
| SKIPPED (ERROR)                                  | -2                                       |
| FAILED                                           | 0                                        |

These status' only replicate the Kogito [DecisionEvaluationStatus usage](https://github.com/kiegroup/drools/blob/7373d109e9020535f5f1c727852946405ea21912/kie-dmn/kie-dmn-core/src/main/java/org/kie/dmn/core/impl/DMNRuntimeImpl.java#L669) and do not represent any business logic from the underlying DMN, that must of course be encoded in the result DLL directly. 

[^1]: The NOT_FOUND status is provided by the library and is for the case where a _dmnEvalStatus field is provided in the ddl but this decision name that does not exist in the dmn.

### Debug mode

Use debugMode when calling evaluate to force the full DMNResult structure (without results) to be written out into an additional debugMdoe field, in the case where no issues are present this is likely overkill and should be kept for debug information only.

In this mode the output DDL more closely mimics the Kogito DMNResult, the two output types are not compatible. 

The JSON provider when in debug mode serializes the entire DMNResult structure, when not the structure mimics the output of the Struct ddl counterpart e.g.:

```json
{"eval":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a1":"b1"}}]}}
```

becomes:

```json
[{"decisionId":"_5BD6B443-5DB7-4CA4-84E2-AC86D643FB15","decisionName":"eval","result":{"top1":"0a","strings":["a0i","b0i","c0i","d0i"],"structs":[{"a":"0","b":2061584302.16,"d":{"a":true,"b":true},"c":{"a1":"b1"}}]},"messages":[],"evaluationStatus":"SUCCEEDED"}]
```