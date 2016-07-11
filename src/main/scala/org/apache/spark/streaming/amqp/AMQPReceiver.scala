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

package org.apache.spark.streaming.amqp

import java.util.concurrent.ConcurrentHashMap

import io.vertx.core.{AsyncResult, Handler, Vertx}
import io.vertx.proton._
import org.apache.qpid.proton.amqp.messaging.{Accepted, Rejected}
import org.apache.qpid.proton.amqp.transport.ErrorCondition
import org.apache.qpid.proton.amqp.{Symbol => AmqpSymbol}
import org.apache.qpid.proton.message.Message
import org.apache.spark.internal.Logging
import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming.receiver.{BlockGenerator, BlockGeneratorListener, Receiver}

import scala.collection.mutable

/**
 * Receiver for getting messages from an AMQP sender node
 *
 * @param host					    AMQP container hostname or IP address to connect
 * @param port					    AMQP container port to connect
 * @param address				    AMQP node address on which receive messages
 * @param messageConverter  Callback for converting AMQP message to custom type at application level
 * @param storageLevel	    RDD storage level
 */
private[streaming]
class AMQPReceiver[T](
      host: String,
      port: Int,
      address: String,
      messageConverter: Message => Option[T],
      storageLevel: StorageLevel
    ) extends Receiver[T](storageLevel) with Logging {

  private final val AmqpRecvError = "org.apache:amqp-recv-error"
  private final val AmqpRecvThrottling = "Throttling : Max rate limit exceeded"

  private var rateController: AMQPRateController = _

  private var vertx: Vertx = _
  
  private var client: ProtonClient = _
  
  private var connection: ProtonConnection = _

  private var receiver: ProtonReceiver = _

  private var blockGenerator: BlockGenerator = _

  private var deliveryBuffer: mutable.ArrayBuffer[ProtonDelivery] = _

  private var blockDeliveryMap: ConcurrentHashMap[StreamBlockId, Array[ProtonDelivery]] = _

  def onStart() {

    logInfo("onStart")

    deliveryBuffer = new mutable.ArrayBuffer[ProtonDelivery]()

    blockDeliveryMap = new ConcurrentHashMap[StreamBlockId, Array[ProtonDelivery]]()

    blockGenerator = supervisor.createBlockGenerator(new GeneratedBlockHandler())
    blockGenerator.start()

    vertx = Vertx.vertx()
    
    val options: ProtonClientOptions = new ProtonClientOptions()
    
    client = ProtonClient.create(vertx)
    
    client.connect(options, host, port, new Handler[AsyncResult[ProtonConnection]] {
      override def handle(ar: AsyncResult[ProtonConnection]): Unit = {
        
        if (ar.succeeded()) {

          connection = ar.result()
          processConnection(connection)
          
        } else {

        }
        
      }
    })

  }
  
  def onStop() {

    logInfo("onStop")

    if (blockGenerator != null && !blockGenerator.isStopped()) {
      blockGenerator.stop()
    }

    if (receiver != null) {
      receiver.close()
    }

    if (connection != null) {
      connection.close()
    }

    if (vertx != null) {
      vertx.close()
    }
  }

  /**
    * Process the connection established with the AMQP source
    *
    * @param connection     AMQP connection instance
    */
  private def processConnection(connection: ProtonConnection): Unit = {

    connection
      .closeHandler(new Handler[AsyncResult[ProtonConnection]] {
        override def handle(ar: AsyncResult[ProtonConnection]): Unit = {
          restart("Connection closed")
        }
      })
      .open()

    receiver = connection
      .createReceiver(address)
      .setAutoAccept(false)
      .handler(new ProtonMessageHandler() {
        override def handle(delivery: ProtonDelivery, message: Message): Unit = {

          rateController.acquire(delivery, message)
        }
      })

    rateController = new AMQPPrefetchRateController(blockGenerator.getCurrentLimit)
    rateController.init()

    receiver.open()
  }

  /**
    * AMQP rate controller implementation using "prefetch"
    * @param maxRateLimit       Max rate for receiving messages
    */
  private final class AMQPPrefetchRateController(
                         maxRateLimit: Long
                         ) extends AMQPRateController(maxRateLimit) {

    override def doInit(): Unit = {

      // if MaxValue it means no max rate limit specified in the Spark configuration
      // so the prefetch isn't explicitly set but default Vert.x Proton value is used
      if (maxRateLimit != Long.MaxValue)
        receiver.setPrefetch(maxRateLimit.toInt)

      super.doInit()
    }

    override def doAcquire(delivery: ProtonDelivery, message: Message): Unit = {

      // permit acquired, add message
      if (blockGenerator.isActive()) {

        // only AMQP message will be stored into BlockGenerator internal buffer;
        // delivery is passed as metadata to onAddData and saved here internally
        blockGenerator.addDataWithCallback(message, delivery)
      }

      super.doAcquire(delivery, message)
    }

    override def doThrottlingStart(delivery: ProtonDelivery, message: Message): Unit = {

      logInfo("onThrottlingStart")
      super.doThrottlingStart(delivery, message)
    }

    override def doThrottlingEnd(delivery: ProtonDelivery, message: Message): Unit = {

      logInfo("onThrottlingEnd")
      super.doThrottlingEnd(delivery, message)
    }

    override def doThrottling(delivery: ProtonDelivery, message: Message): Unit = {

      // during throttling (max rate limit exceeded), all messages are rejected
      val rejected: Rejected = new Rejected()
      val errorCondition: ErrorCondition = new ErrorCondition(AmqpSymbol.valueOf(AmqpRecvError), AmqpRecvThrottling)
      rejected.setError(errorCondition)
      delivery.disposition(rejected, true)

      super.doThrottling(delivery, message)
    }
  }

  /**
    * Handler for blocks generated by the block generator
    */
  private final class GeneratedBlockHandler extends BlockGeneratorListener {

    def onAddData(data: Any, metadata: Any): Unit = {

      logDebug(data.toString())

      if (metadata != null) {

        // adding delivery into internal buffer
        val delivery = metadata.asInstanceOf[ProtonDelivery]
        deliveryBuffer += delivery
      }
    }

    def onGenerateBlock(blockId: StreamBlockId): Unit = {

      logInfo("onGenerateBlock")

      // cloning internal delivery buffer and mapping it to the generated block
      val deliveryBufferSnapshot = deliveryBuffer.toArray
      blockDeliveryMap.put(blockId, deliveryBufferSnapshot)
      deliveryBuffer.clear()
    }

    def onPushBlock(blockId: StreamBlockId, arrayBuffer: mutable.ArrayBuffer[_]): Unit = {

      logInfo("onPushBlock")

      // buffer contains AMQP Message instances
      val messages = arrayBuffer.asInstanceOf[mutable.ArrayBuffer[Message]]

      try {

        // storing result conversion from AMQP Message instances
        // by the application provided converter
        store(messages.flatMap(x => messageConverter(x)))

        // for the deliveries related to the current generated block
        blockDeliveryMap.get(blockId).foreach(delivery => {

          // for unsettled messages, send ACCEPTED delivery status
          if (!delivery.remotelySettled()) {
            delivery.disposition(Accepted.getInstance(), true)
          }
        })

      } catch {

        case ex: Throwable => logError(ex.getMessage(), ex)
      } finally {

      }

    }

    def onError(message: String, throwable: Throwable): Unit = {
      logError(message, throwable)
    }
  }

  implicit def functionToHandler[A](f: A => Unit): Handler[A] = new Handler[A] {
    override def handle(event: A): Unit = {
      f(event)
    }
  }

}