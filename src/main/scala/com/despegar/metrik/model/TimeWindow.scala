/*
 * =========================================================================================
 * Copyright © 2014 the metrik project <https://github.com/hotels-tech/metrik>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package com.despegar.metrik.model

import com.despegar.metrik.store.{ CassandraStatisticSummaryStore, StatisticSummaryStore, CassandraHistogramBucketStore, HistogramBucketStore }
import org.HdrHistogram.Histogram
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import com.despegar.metrik.model.HistogramBucket._
import scala.concurrent.ExecutionContext.Implicits.global
import com.despegar.metrik.store.HistogramBucketSupport
import com.despegar.metrik.store.StatisticSummarySupport
import scala.concurrent.duration._
import com.despegar.metrik.util.Logging

case class TimeWindow(duration: Duration, previousWindowDuration: Duration, shouldStoreTemporalHistograms: Boolean = true) extends HistogramBucketSupport with StatisticSummarySupport with Logging {

  def process(metric: String) = {
    log.debug(s"Processing window of $duration for metric $metric...")
    //retrieve the temporal histogram buckets from previous window
    val previousWindowBuckets = histogramBucketStore.sliceUntilNow(metric, previousWindowDuration)

    //group histograms in buckets of my window duration
    val groupedHistogramBuckets = previousWindowBuckets map (buckets ⇒ buckets.groupBy(_.timestamp / duration.toMillis))

    //filter out buckets already processed. we don't want to override our precious buckets with late data
    val lastBucketNumber = statisticSummaryStore.getLast(metric, duration) map (summary => summary.timestamp / duration.toMillis)
    val filteredGroupedHistogramBuckets = lastBucketNumber map (bucketNumber => groupedHistogramBuckets map (histogramsBuckets => histogramsBuckets.filterNot(_._1 <= bucketNumber)))

    //sum histograms on each bucket
    val resultingBuckets = filteredGroupedHistogramBuckets map (buckets => buckets map ( x ⇒ x.collect { case (bucketNumber, histogramBuckets) ⇒ HistogramBucket(bucketNumber, duration, histogramBuckets) }.toSeq))

    //store temporal histogram buckets for next window if needed
    if (shouldStoreTemporalHistograms) {
      resultingBuckets map (buckets ⇒ histogramBucketStore.store(metric, duration, buckets))
    }

    //calculate the statistic summaries (percentiles, min, max, etc...)
    val statisticsSummaries = resultingBuckets.map(buckets ⇒ buckets map (_.summary))

    //store the statistic summaries
    statisticsSummaries map (summaries ⇒ statisticSummaryStore.store(metric, duration, summaries))

    //remove previous histogram buckets
    previousWindowBuckets map (windows ⇒ histogramBucketStore.remove(metric, previousWindowDuration, windows))
  }

  private def alreadyProcessed(metric: String, duration: Duration): PartialFunction[(Long, Seq[HistogramBucket]), Future[Boolean]] = {
    case (bucketNumber, _) ⇒ {
      val asyncSummary = statisticSummaryStore.getLast(metric, duration)
      asyncSummary map (summary => summary.timestamp / duration.toMillis >= bucketNumber)
    } //how? a query over the statistics summaries to get the last one? -> yes
  }

}