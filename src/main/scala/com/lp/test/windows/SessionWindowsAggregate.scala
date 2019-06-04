package com.lp.test.windows

import com.lp.test.utils.ConfigUtils
import org.apache.flink.api.common.functions.{AggregateFunction, RichMapFunction}
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.windowing.assigners.{ProcessingTimeSessionWindows, SessionWindowTimeGapExtractor}
import org.apache.flink.streaming.api.windowing.triggers.{Trigger, TriggerResult}
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer

/**
  * <p/> 
  * <li>Description: 会话窗口具有动态间隙的处理时间进行</li>
  *
  * 在会话窗口中按活动会话分配器组中的数据元。与翻滚窗口和滑动窗口相比，会话窗口不重叠并且没有固定的开始和结束时间。
  * 相反，当会话窗口在一段时间内没有接收到数据元时，即当发生不活动的间隙时，会关闭会话窗口。会话窗口分配器可以配置
  * 静态会话间隙或 会话间隙提取器函数，该函数定义不活动时间段的长度。当此期限到期时，当前会话将关闭，后续数据元将分配给新的会话窗口。
  * <li>@author: lipan@cechealth.cn</li> 
  * <li>Date: 2019-05-10 16:34</li> 
  */
object SessionWindowsAggregate {


  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    env.getConfig.setAutoWatermarkInterval(1000) //watermark间隔时间

    //设置事件事件
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val kafkaConfig = ConfigUtils.apply("kv")

    val kafkaConsumer = new FlinkKafkaConsumer(kafkaConfig._1,
      new SimpleStringSchema(), //自定义反序列化器
      kafkaConfig._2)
      .setStartFromLatest()

    import org.apache.flink.api.scala._
    val aggregate = env
      .addSource(kafkaConsumer)
      .map(new RichMapFunction[String, (String, Long)] {
        override def map(value: String): (String, Long) = {
          val splits = value.split(" ")
          (splits(0), splits(1).toLong)
        }
      })
      .keyBy(0)
      //.windowAll(ProcessingTimeSessionWindows.withGap(Time.seconds(10)))
      //.windowAll(EventTimeSessionWindows.withGap(Time.seconds(10)))
      .windowAll(ProcessingTimeSessionWindows.withDynamicGap(new SessionWindowTimeGapExtractor[(String, Long)] {
      override def extract(element: (String, Long)): Long = {
        // 当发生不活动的间隙时间价格长度10s
        10000
      }
    }))
      .trigger(new CustomProcessTimeTrigger)
      .aggregate(new AverageAggregate)

    aggregate.print()
    env.execute("SessionWindowsAggregate")

  }


  /**
    * 自定义聚合函数
    */
  class AverageAggregate extends AggregateFunction[(String, Long), (Long, Long), Double] {
    override def createAccumulator() = (0L, 0L)

    override def add(value: (String, Long), accumulator: (Long, Long)) =
      (accumulator._1 + value._2, accumulator._2 + 1L)

    override def getResult(accumulator: (Long, Long)) = accumulator._1 / accumulator._2

    override def merge(a: (Long, Long), b: (Long, Long)) =
      (a._1 + b._1, a._2 + b._2)
  }


  /**
    * 自定义触发器
    *
    * CONTINUE： 没做什么，
    * FIRE：触发​​计算，
    * PURGE：清除窗口中的数据元和
    * FIRE_AND_PURGE：触发​​计算并清除窗口中的数据元。
    */
  class CustomProcessTimeTrigger extends Trigger[(String, Long), TimeWindow] {

    var flag = 0

    /**
      * 进入窗口的每个元素都会调用该方法
      *
      * @param element
      * @param timestamp
      * @param window
      * @param ctx
      * @return
      */
    override def onElement(element: (String, Long), timestamp: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = {

      //注册系统时间回调。当前系统时间超过指定时间
      ctx.registerProcessingTimeTimer(window.maxTimestamp)
      // CONTINUE是代表不做输出，也就是，此时我们想要实现比如10条输出一次，
      // 而不是窗口结束再输出就可以在这里实现。
      if (flag > 9) {
        System.out.println("onElement : " + flag + "触发计算，保留Window内容")
        flag = 0
        TriggerResult.FIRE
      }
      else
        flag += 1
      System.out.println("onElement : " + element)
      TriggerResult.CONTINUE
    }

    /**
      * 在注册的事件时间计时器触发时调用该方法。
      *
      * @param time
      * @param window
      * @param ctx
      * @return
      */
    override def onProcessingTime(time: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = TriggerResult.CONTINUE

    /**
      * 在注册的处理时间计时器触发时调用该方法
      *
      * @param time
      * @param window
      * @param ctx
      * @return
      */
    override def onEventTime(time: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = TriggerResult.FIRE

    override def clear(window: TimeWindow, ctx: Trigger.TriggerContext) = ctx.deleteProcessingTimeTimer(window.maxTimestamp)

    /**
      * 如果此触发器支持合并触发器状态，则返回true
      * @return
      */
    override def canMerge()={
      true
    }
    /**
      * 该onMerge()方法与状态触发器相关，并且当它们的相应窗口合并时合并两个触发器的状态，例如当使用会话窗口时。
      *
      * @param window
      * @param ctx
      */
    override def onMerge(window: TimeWindow, ctx: Trigger.OnMergeContext): Unit = {

      val windowMaxTimestamp = window.maxTimestamp
      if (windowMaxTimestamp > ctx.getCurrentProcessingTime)
        ctx.registerProcessingTimeTimer(windowMaxTimestamp)
    }

    override def toString: String = "ProcessingTimeTrigger()"


  }

}
