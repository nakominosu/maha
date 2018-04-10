// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.service.curators

import java.util.concurrent.Callable

import com.yahoo.maha.core._
import com.yahoo.maha.core.bucketing.BucketParams
import com.yahoo.maha.core.request.{Field, ReportingRequest}
import com.yahoo.maha.parrequest2.{GeneralError, ParCallable}
import com.yahoo.maha.parrequest2.future.ParRequest
import com.yahoo.maha.service.error.MahaServiceBadRequestException
import com.yahoo.maha.service.utils.MahaRequestLogHelper
import com.yahoo.maha.service.{MahaRequestContext, MahaService}
import grizzled.slf4j.Logging

import scala.util.Try

/**
  * DrilldownCurator : Given an input Request with a Drilldown Json config,
  * create a new Request using the input Drilldown primary dimension.
  * @author ryanwagner
  */

/**
  *
  */
object DrilldownCurator {
  val name: String = "drilldown"
}

/**
  *
  * @param requestModelValidator: Used to validate the input RequestModel
  */
class DrilldownCurator (override val requestModelValidator: CuratorRequestModelValidator = NoopCuratorRequestModelValidator) extends Curator with Logging {

  override val name: String = DrilldownCurator.name
  override val level: Int = 1
  override val priority: Int = 0
  override val isSingleton: Boolean = true
  private val INCLUDE_ROW_COUNT_DRILLDOWN = false

  /**
    * Verify the input reportingRequest generates a valid requestModel.
    * If so, return this requestModel for the primary request.
    * @param registryName: Name of the current registry
    * @param bucketParams: Request bucketing configuration
    * @param reportingRequest: Input reporting request
    * @param mahaService: Service used to generate the request model
    * @param mahaRequestLogHelper: For error logging
    * @return requestModel
    */
  private def validateReportingRequest(registryName: String,
                                       bucketParams: BucketParams,
                                       reportingRequest: ReportingRequest,
                                       mahaService: MahaService,
                                       mahaRequestLogHelper: MahaRequestLogHelper): (RequestModel, IndexedSeq[Field]) = {
    require(DrilldownConfig.validCubes.contains(reportingRequest.cube), "Cannot drillDown using given source cube " + reportingRequest.cube)

    val requestModelResultTry: Try[RequestModelResult] = mahaService.generateRequestModel(registryName, reportingRequest, bucketParams, mahaRequestLogHelper)
    require(requestModelResultTry.isSuccess, "Input ReportingRequest was invalid due to " + requestModelResultTry.failed.get.getMessage)
    (requestModelResultTry.get.model,
      (for(col <- requestModelResultTry.get.model.bestCandidates.get.requestCols
          if requestModelResultTry.get.model.bestCandidates.get.factColMapping.contains(col))
            yield Field(requestModelResultTry.get.model.bestCandidates.get.factColMapping(col), None, None)).toIndexedSeq)
  }

  /**
    * Check if generated requestModel requestCols includes
    * the primary key for its own most granular requested table.
    * If this primary key exists in the request, return its alias
    * to be used in the secondary request's included columns.
    * @param requestModel: Request model with tree of granular tables
    * @return primaryKeyAlias
    */
  private def mostGranularPrimaryKey(requestModel: RequestModel): Option[Field] = {
    val mostGranularPrimaryKey : String = if (requestModel.dimensionsCandidates.nonEmpty) requestModel.dimensionsCandidates.last.dim.primaryKeyByAlias else ""

    if (mostGranularPrimaryKey.isEmpty) None else Some(Field(mostGranularPrimaryKey, None, None))
  }

  /**
    * Copy the current ReportingRequest with:
    * - Requested DrillDown Dim as primary.
    * - Primary key of primary table.
    * - All metrics (facts).
    * @param reportingRequest: Original reporting request to transform
    * @param factFields: All fact fields from the request model
    * @param primaryKeyField: Primary key from most granular table
    */
  private def drilldownReportingRequest(reportingRequest: ReportingRequest,
                                                       factFields: IndexedSeq[Field],
                                                       primaryKeyField: Field): ReportingRequest = {
    val drilldownConfig: DrilldownConfig = DrilldownConfig.parse(reportingRequest)
    val allSelectedFields : IndexedSeq[Field] = (IndexedSeq(DrilldownConfig.parse(reportingRequest).dimension, primaryKeyField).filter{_!=null} ++ factFields).distinct
    reportingRequest.copy(cube = drilldownConfig.cube
      , selectFields = allSelectedFields
      , sortBy = drilldownConfig.ordering
      , rowsPerPage = drilldownConfig.maxRows.toInt
    , filterExpressions = reportingRequest.filterExpressions
    , includeRowCount = INCLUDE_ROW_COUNT_DRILLDOWN)
  }

  /**
    * With the returned values on the drilldown, create a
    * new reporting request.
    * @param reportingRequest: ReportingRequest to add primary key filter
    * @param drilldownDimName: Name of primary drilldown dimension
    * @param inputFieldValues: All values found in the initial request
    */
  def insertValuesIntoDrilldownRequest(reportingRequest: ReportingRequest,
                                       drilldownDimName: String,
                                       inputFieldValues: List[String]): ReportingRequest = {
    reportingRequest.copy(filterExpressions = (reportingRequest.filterExpressions ++ IndexedSeq(InFilter(drilldownDimName, inputFieldValues))).distinct
    , includeRowCount = INCLUDE_ROW_COUNT_DRILLDOWN)
  }

  /**
    *
    * @param registryName: Name of current reporting registry
    * @param bucketParams: Bucket configuration parameters
    * @param reportingRequest: Original reporting request to modify
    * @param mahaService: Service with registry and all initial parameters
    * @param mahaRequestLogHelper: Error logging
    * @return Modified reporting request with drilldown
    */
  def implementDrilldownRequestMinimization(registryName: String,
              bucketParams: BucketParams,
              reportingRequest: ReportingRequest,
              mahaService: MahaService,
              mahaRequestLogHelper: MahaRequestLogHelper): ReportingRequest = {
    val (rm, fields) : (RequestModel, IndexedSeq[Field]) = validateReportingRequest(registryName, bucketParams, reportingRequest, mahaService, mahaRequestLogHelper)
    val primaryField : Field = mostGranularPrimaryKey(rm).orNull

    val rr = drilldownReportingRequest(reportingRequest, fields, primaryField)
    rr
  }

  /**
    *
    * @param requestModelResultTry: Attempted request model execution
    * @param mahaRequestLogHelper: For error logging
    * @param mahaRequestContext: Local request context for the maha Service
    * @param mahaService: Service with all initial parameters
    * @param parRequestLabel: Label for the parallel request, in case of error logging
    * @return
    */
  def verifyRequestModelResult(requestModelResultTry: Try[RequestModelResult],
                               mahaRequestLogHelper: MahaRequestLogHelper,
                               mahaRequestContext: MahaRequestContext,
                               mahaService: MahaService,
                               parRequestLabel: String) : Either[GeneralError, CuratorResult] = {
    if(requestModelResultTry.isFailure) {
      val message = requestModelResultTry.failed.get.getMessage
      mahaRequestLogHelper.logFailed(message)
      GeneralError.either[CuratorResult](parRequestLabel, message, MahaServiceBadRequestException(message, requestModelResultTry.failed.toOption))
    } else {
      requestModelValidator.validate(mahaRequestContext, requestModelResultTry.get)
      val requestResultTry = mahaService.processRequestModel(mahaRequestContext.registryName
        , requestModelResultTry.get.model, mahaRequestLogHelper)
      new Right[GeneralError, CuratorResult](CuratorResult(requestResultTry, requestModelResultTry.get))
    }
  }

  /**
    *
    * @param mahaRequestContext: Context for the current reporting request
    * @param mahaService: Service with all reporting request configurations
    * @param mahaRequestLogHelper: For error logging
    * @return result of the reportingRequest report generation attempt
    */
  override def process(mahaRequestContext: MahaRequestContext
                       , mahaService: MahaService
                       , mahaRequestLogHelper: MahaRequestLogHelper): ParRequest[CuratorResult] = {

    val registryConfig = mahaService.getMahaServiceConfig.registry(mahaRequestContext.registryName)
    val parallelServiceExecutor = registryConfig.parallelServiceExecutor
    val parRequestLabel = "processDrillDownCurator"

    val parRequest = parallelServiceExecutor.parRequestBuilder[CuratorResult].setLabel(parRequestLabel).
      setParCallable(ParCallable.from[Either[GeneralError, CuratorResult]](
        new Callable[Either[GeneralError, CuratorResult]](){
          override def call(): Either[GeneralError, CuratorResult] = {

            val requestModelResultTry: Try[RequestModelResult] = mahaService.generateRequestModel(
              mahaRequestContext.registryName, mahaRequestContext.reportingRequest, mahaRequestContext.bucketParams
              , mahaRequestLogHelper)

            verifyRequestModelResult(requestModelResultTry, mahaRequestLogHelper, mahaRequestContext, mahaService, parRequestLabel)
          }
        }
      )).build()
    val firstRequest : ParRequest[CuratorResult] = parRequest

    val parRequest2 : ParRequest[CuratorResult] = parallelServiceExecutor.parRequestBuilder[CuratorResult].setLabel(parRequestLabel).
      setParCallable(ParCallable.from[Either[GeneralError, CuratorResult]](
        new Callable[Either[GeneralError, CuratorResult]](){
          override def call(): Either[GeneralError, CuratorResult] = {

            require(firstRequest.get.isRight, "First par request failed, cannot build the second! " + firstRequest.get.left.get.message)

            val drillDownConfig = DrilldownConfig.parse(mahaRequestContext.reportingRequest)

            val rowList = firstRequest.get.right.get.requestResultTry.get.queryPipelineResult.rowList
            var values : Set[String] = Set.empty
            rowList.foreach{
              row => values = values ++ List(row.cols(row.aliasMap(drillDownConfig.dimension.field)).toString)
            }

            val newReportingRequest = implementDrilldownRequestMinimization(mahaRequestContext.registryName, mahaRequestContext.bucketParams, mahaRequestContext.reportingRequest, mahaService, mahaRequestLogHelper)

            val newRequestWithInsertedFilter = insertValuesIntoDrilldownRequest(newReportingRequest, drillDownConfig.dimension.field, values.toList)

            val requestModelResultTry: Try[RequestModelResult] = mahaService.generateRequestModel(
              mahaRequestContext.registryName, newRequestWithInsertedFilter, mahaRequestContext.bucketParams
              , mahaRequestLogHelper)

            verifyRequestModelResult(requestModelResultTry, mahaRequestLogHelper, mahaRequestContext, mahaService, parRequestLabel)
          }
        }
      )).build()

    parRequest2
  }

}
