package com.sparkutils.dmn.kogito

trait Constants {

  val evalStatusEnding = "_dmnEvalStatus"

  // The errors in intellij are not real
  val NOT_FOUND: Byte = -6.toByte // DDL has a decision which isn't in the DMN, possibly a typo or the dmn decision was removed / not there yet
  val NOT_EVALUATED: Byte = -5.toByte // shouldn't happen
  val EVALUATING: Byte = -4.toByte // shouldn't happen as it'll be overwritten in KogitoDDLResult
  val SUCCEEDED: Byte  = 1.toByte
  val SKIPPED_WARN: Byte  = -3.toByte
  val SKIPPED_ERROR: Byte  = -2.toByte
  val FAILED: Byte  = 0.toByte

}

object Constants extends Constants

object Types {
  type MAP = java.util.Map[String, Object]
}

object Errors {
  val CONTEXT_PROVIDER_PARSE = "FieldExpression is invalid SQL"
}