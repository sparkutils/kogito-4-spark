package com.sparkutils.dmn.kogito

@SerialVersionUID(1L)
case class KogitoFeelEvent(severity: String, message: String, line: Int, column: Int, sourceException: String, offendingSymbol: String) extends Serializable

@SerialVersionUID(1L)
case class KogitoMessage(sourceId: String, sourceReference: String, exception: String, feelEvent: KogitoFeelEvent) extends Serializable

/**
 * Represents the DDL provider output type for debugMode
 * @param decisionId
 * @param decisionName
 * @param hasErrors
 * @param messages
 */
@SerialVersionUID(1L)
case class KogitoResult(decisionId: String, decisionName: String, hasErrors: Boolean, messages: Seq[KogitoMessage], evaluationStatus: String) extends Serializable