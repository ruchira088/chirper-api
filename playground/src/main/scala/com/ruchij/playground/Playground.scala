package com.ruchij.playground

import java.util.UUID

import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.github.javafaker.Faker
import com.ruchij.shared.config.KafkaConfiguration
import com.ruchij.shared.ec.IOExecutionContextImpl
import com.ruchij.shared.kafka.{KafkaMessage, KafkaTopic}
import com.ruchij.shared.kafka.KafkaTopic.UserCreated
import com.ruchij.shared.kafka.admin.KafkaAdministratorImpl
import com.ruchij.shared.kafka.consumer.{KafkaConsumer, KafkaConsumerImpl}
import com.ruchij.shared.kafka.producer.{KafkaProducer, KafkaProducerImpl}
import com.ruchij.shared.kafka.schemaregistry.SchemaRegistryImpl
import com.ruchij.shared.models.User
import com.ruchij.shared.utils.{StringUtils, SystemUtilities}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.security.plain.PlainLoginModule
import org.joda.time.DateTime
import play.api.libs.json.Json
import play.api.libs.ws.ahc.{AhcWSClient, StandaloneAhcWSClient}
import play.shaded.ahc.org.asynchttpclient.{AsyncHttpClient, DefaultAsyncHttpClient}

import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

object Playground {
  private val logger: Logger = Logger[Playground.type]

  def main(args: Array[String]): Unit = {
    val kafkaConfiguration = KafkaConfiguration.parse(ConfigFactory.load()).get

    implicit val actorSystem: ActorSystem = ActorSystem("playground")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContextExecutor: ExecutionContextExecutor = actorSystem.dispatcher

    implicit val systemUtilities: SystemUtilities = SystemUtilities

    val wsClient = AhcWSClient()

    println {
      Await.result(new KafkaAdministratorImpl(kafkaConfiguration, new IOExecutionContextImpl(actorSystem)).listTopics(), Duration.Inf)
    }

    wsClient.close()
    materializer.shutdown()

    actorSystem.terminate()
      .onComplete {
        case Success(_) => sys.exit()
        case _ => sys.exit(1)
      }
//
//    val faker = Faker.instance()
//
//    val kafkaProducer: KafkaProducer = new KafkaProducerImpl(kafkaConfiguration)
//
//    Source
//      .tick(0 seconds, 500 milliseconds, (): Unit)
//      .map { _ =>
//        User(
//          UUID.randomUUID(),
//          DateTime.now(),
//          faker.name().username(),
//          faker.name().firstName(),
//          None,
//          faker.internet().emailAddress(),
//          None
//        )
//      }
//      .mapAsync(1) { user =>
//        kafkaProducer.publish(KafkaMessage(user)).map(_ -> user)
//      }
//      .runWith {
//        Sink.foreach {
//          case (recordMetadata: RecordMetadata, user: User) =>
//            logger.info {
//              s"userId = ${user.userId}, offset = ${recordMetadata.offset()}, partition = ${recordMetadata.partition()}"
//            }
//        }
//      }
//
//      startConsumer(UserCreated, kafkaConfiguration, "consumer-0")
  }

  def startConsumer(kafkaTopic: KafkaTopic[_], kafkaConfiguration: KafkaConfiguration, id: String)(
    implicit actorSystem: ActorSystem,
    actorMaterializer: ActorMaterializer,
    systemUtilities: SystemUtilities,
    executionContext: ExecutionContext
  ): Future[Done] =
    new KafkaConsumerImpl(kafkaConfiguration)
      .subscribe(kafkaTopic)
      .mapAsync(1) {
        case (user, committableOffset) =>
          println {
            s"GroupId: ${committableOffset.partitionOffset.key.groupId}, Partition: ${committableOffset.partitionOffset.key.partition}, Offset: ${committableOffset.partitionOffset.offset}, Id: $id, Data: $user"
          }
          committableOffset.commitScaladsl()
      }
      .runWith(Sink.ignore)
}