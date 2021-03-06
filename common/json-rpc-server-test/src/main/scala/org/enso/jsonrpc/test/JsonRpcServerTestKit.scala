package org.enso.jsonrpc.test

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import io.circe.Json
import io.circe.parser.parse
import org.enso.jsonrpc.{ClientControllerFactory, JsonRpcServer, Protocol}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Assertion, BeforeAndAfterAll, BeforeAndAfterEach}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test kit for testing JSON RPC servers.
  */
abstract class JsonRpcServerTestKit
    extends TestKit(ActorSystem("TestSystem"))
    with ImplicitSender
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val interface       = "127.0.0.1"
  var address: String = _

  var server: JsonRpcServer       = _
  var binding: Http.ServerBinding = _

  def protocol: Protocol

  def clientControllerFactory: ClientControllerFactory

  override def beforeEach(): Unit = {

    server  = new JsonRpcServer(protocol, clientControllerFactory)
    binding = Await.result(server.bind(interface, port = 0), 3.seconds)
    address = s"ws://$interface:${binding.localAddress.getPort}"
  }

  override def afterEach(): Unit = {
    val _ = binding.unbind()
  }

  class WsTestClient(address: String) {
    private var inActor: ActorRef   = _
    private val outActor: TestProbe = TestProbe()
    private val source: Source[Message, NotUsed] = Source
      .actorRef[String](
        PartialFunction.empty,
        PartialFunction.empty,
        1,
        OverflowStrategy.fail
      )
      .mapMaterializedValue { actorRef =>
        inActor = actorRef
        NotUsed
      }
      .map { txt: String =>
        TextMessage(txt)
      }
    private val sink: Sink[Message, NotUsed] = Flow[Message]
      .map {
        case TextMessage.Strict(s) => s
      }
      .to(Sink.actorRef[String](outActor.ref, PoisonPill, { _: Any =>
        PoisonPill
      }))
    private val flow = Flow.fromSinkAndSource(sink, source)

    Http()
      .singleWebSocketRequest(WebSocketRequest(address), flow)

    def send(message: String): Unit = {
      inActor ! message
    }

    def send(json: Json): Unit = send(json.noSpaces)

    def expectMessage(): String =
      outActor.expectMsgClass[String](classOf[String])

    def expectJson(json: Json): Assertion = {
      val parsed = parse(expectMessage())
      parsed shouldEqual Right(json)
    }

    def expectNoMessage(): Unit = outActor.expectNoMessage()
  }
}
