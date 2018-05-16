/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.nio.ByteBuffer
import java.util.Properties

import scala.language.existentials

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.shuffle.ShuffleWriter

/**
 * A ShuffleMapTask divides the elements of an RDD into multiple buckets (based on a partitioner
 * specified in the ShuffleDependency).
 *
 * See [[org.apache.spark.scheduler.Task]] for more information.
 *
 * @param stageId id of the stage this task belongs to
 * @param stageAttemptId attempt id of the stage this task belongs to
 * @param taskBinary broadcast version of the RDD and the ShuffleDependency. Once deserialized,
 *                   the type should be (RDD[_], ShuffleDependency[_, _, _]).
 * @param partition partition of the RDD this task is associated with
 * @param locs preferred task execution locations for locality scheduling
 * @param metrics a [[TaskMetrics]] that is created at driver side and sent to executor side.
 * @param localProperties copy of thread-local properties set by the user on the driver side.
 */

/**
  * add by uwoer
  * 一个ShuffleMapTask会将一个rdd切分为多个buckets
  * （基于ShuffleDependency中指定的partitioner
  * spark1.2之前默认是HashPartitioner；spark1.2之后默认的是SortShuffleManager）
  * 参加：https://www.cnblogs.com/hd-zg/p/6089230.html
  */
private[spark] class ShuffleMapTask(
    stageId: Int,
    stageAttemptId: Int,
    taskBinary: Broadcast[Array[Byte]],
    partition: Partition,
    @transient private var locs: Seq[TaskLocation],
    metrics: TaskMetrics,
    localProperties: Properties)
  extends Task[MapStatus](stageId, stageAttemptId, partition.index, metrics, localProperties)
  with Logging {

  /** A constructor used only in test suites. This does not require passing in an RDD. */
  def this(partitionId: Int) {
    this(0, 0, null, new Partition { override def index: Int = 0 }, null, null, new Properties)
  }

  @transient private val preferredLocs: Seq[TaskLocation] = {
    if (locs == null) Nil else locs.toSet.toSeq
  }

  /**
    * @return   MapStatus  结果汇总是会使用
    */
  override def runTask(context: TaskContext): MapStatus = {
    // Deserialize the RDD using the broadcast variable.
    val deserializeStartTime = System.currentTimeMillis()
    val ser = SparkEnv.get.closureSerializer.newInstance()

    //对task处理的rdd相关的数据进行反序列化操作
    //问题的关键是多个task运行在多个exector中，都是并发或者并行运行的
    //所以一个task怎么能拿到自己要处理的那个rddd的数据呢？
    //这里是通过broadcast variable 直接拿到
    val (rdd, dep) = ser.deserialize[(RDD[_], ShuffleDependency[_, _, _])](
      ByteBuffer.wrap(taskBinary.value), Thread.currentThread.getContextClassLoader)
    _executorDeserializeTime = System.currentTimeMillis() - deserializeStartTime

    var writer: ShuffleWriter[Any, Any] = null
    try {
      //获取shuffleManager
      val manager = SparkEnv.get.shuffleManager
      //从shuffleManager中获取ShuffleWriter
      writer = manager.getWriter[Any, Any](dep.shuffleHandle, partitionId, context)

      //调用rdd的iterator()方法,并且传入了当前task要处理的那个partition
      //核心逻辑就是在rdd的iterator()方法中，实现针对rdd的某个partition执行我们自己定义的算子或者函数
      // todo 将返回的数据经过HashPartitioner进行分区，写入到自己对应的分区bucket中 这里有问题待分析
      writer.write(rdd.iterator(partition, context).asInstanceOf[Iterator[_ <: Product2[Any, Any]]])

      //返回结果 MapStatus
      //MapStatus里面封装了ShuffleMapTask计算后的数据，这些数据存储在BlockManager中
      //BlockManager是spark底层内存、数据管理的组件
      writer.stop(success = true).get
    } catch {
      case e: Exception =>
        try {
          if (writer != null) {
            writer.stop(success = false)
          }
        } catch {
          case e: Exception =>
            log.debug("Could not stop writer", e)
        }
        throw e
    }
  }

  override def preferredLocations: Seq[TaskLocation] = preferredLocs

  override def toString: String = "ShuffleMapTask(%d, %d)".format(stageId, partitionId)
}
