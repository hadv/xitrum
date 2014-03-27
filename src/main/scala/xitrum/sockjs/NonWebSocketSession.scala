package xitrum.sockjs

import scala.collection.mutable.ArrayBuffer

import akka.actor.{Actor, ActorRef, ActorSystem, Props, ReceiveTimeout, Terminated}
import scala.concurrent.duration._

import xitrum.{Action, Config, SockJsText}

// There are 2 kinds of non-WebSocket client: receiver and sender
// receiver/sender client <-> NonWebSocketSessionActor <-> SockJsAction
// (See SockJsActions.scala)
//
// For WebSocket:
// receiver/sender client <-> SockJsAction

case object SubscribeFromReceiverClient
case object AbortFromReceiverClient

case class  MessagesFromSenderClient(messages: Seq[String])

case class  MessageFromHandler(message: String)
case object CloseFromHandler

case object SubscribeResultToReceiverClientAnotherConnectionStillOpen
case object SubscribeResultToReceiverClientClosed
case class  SubscribeResultToReceiverClientMessages(messages: Seq[String])
case object SubscribeResultToReceiverClientWaitForMessage

case class  NotificationToReceiverClientMessage(message: String)
case object NotificationToReceiverClientHeartbeat
case object NotificationToReceiverClientClosed

object NonWebSocketSession {
  // The session must time out after 5 seconds of not having a receiving connection
  // http://sockjs.github.com/sockjs-protocol/sockjs-protocol-0.3.3.html#section-46
  private val TIMEOUT_CONNECTION = 5.seconds

  private val TIMEOUT_CONNECTION_MILLIS = TIMEOUT_CONNECTION.toMillis
}

/**
 * There should be at most one subscriber:
 * http://sockjs.github.com/sockjs-protocol/sockjs-protocol-0.3.3.html
 *
 * To avoid out of memory, the actor is stopped when there's no subscriber
 * for a long time. Timeout is also used to check if there's no message
 * for subscriber for a long time.
 * See TIMEOUT_CONNECTION and TIMEOUT_HEARTBEAT in NonWebSocketSessions.
 */
class NonWebSocketSession(var receiverCliento: Option[ActorRef], pathPrefix: String, action: Action) extends Actor {
  import NonWebSocketSession._

  private[this] var sockJsActorRefo: Option[ActorRef] = None

  // Messages from handler to client are buffered here
  private[this] val bufferForClientSubscriber = ArrayBuffer.empty[String]

  // ReceiveTimeout may not occurred if there's frequent Publish, thus we
  // need to manually check if there's no subscriber for a long time.
  // lastSubscribedAt must be Long to avoid Integer overflow, beacuse
  // System.currentTimeMillis() is used.
  private[this] var lastSubscribedAt = 0L

  // Until the timeout occurs, the server must constantly serve the close message
  private[this] var closed = false

  override def preStart() {
    // Attach sockJsActorRef to the current actor, so that sockJsActorRef is
    // automatically stopped when the current actor stops
    val ref = Config.routes.sockJsRouteMap.createSockJsAction(context, pathPrefix)
    context.watch(ref)

    sockJsActorRefo = Some(ref)
    ref ! (self, action)

    lastSubscribedAt = System.currentTimeMillis()

    // At start (see constructor), there must be a receiver client
    val receiverClient = receiverCliento.get

    // Unsubscribed when stopped
    context.watch(receiverClient)

    // Will be set to TIMEOUT_CONNECTION when the receiver client stops
    context.setReceiveTimeout(SockJsAction.TIMEOUT_HEARTBEAT)
  }

  private def unwatchAndStop() {
    receiverCliento.foreach(context.unwatch)
    sockJsActorRefo.foreach { sockJsActorRef =>
      context.unwatch(sockJsActorRef)
      context.stop(sockJsActorRef)
    }
    context.stop(self)
  }

  def receive = {
    // When non-WebSocket receiverClient stops normally after sending data to
    // browser, we need to wait for TIMEOUT_CONNECTION amount of time for the
    // to reconnect. Non-streaming client disconnects everytime. Note that for
    // browser to do garbage collection, streaming client also disconnects after
    // sending a large amount of data (4KB in test mode).
    //
    // See also AbortFromReceiverClient below.
    case Terminated(monitored) =>
      if (sockJsActorRefo == Some(monitored) && !closed) {
        // See CloseFromHandler
        unwatchAndStop()
      } else if (receiverCliento == Some(monitored)){
        context.unwatch(monitored)
        receiverCliento = None
        context.setReceiveTimeout(TIMEOUT_CONNECTION)
      }

    // Similar to Terminated but no TIMEOUT_CONNECTION is needed
    case AbortFromReceiverClient =>
      unwatchAndStop()

    case SubscribeFromReceiverClient =>
      val s = sender()
      if (closed) {
        s ! SubscribeResultToReceiverClientClosed
      } else {
        lastSubscribedAt = System.currentTimeMillis()
        if (receiverCliento.isEmpty) {
          receiverCliento = Some(s)
          context.watch(s)
          context.setReceiveTimeout(SockJsAction.TIMEOUT_HEARTBEAT)

          if (bufferForClientSubscriber.isEmpty) {
            s ! SubscribeResultToReceiverClientWaitForMessage
          } else {
            s ! SubscribeResultToReceiverClientMessages(bufferForClientSubscriber.toList)
            bufferForClientSubscriber.clear()
          }
        } else {
          s ! SubscribeResultToReceiverClientAnotherConnectionStillOpen
        }
      }

    case CloseFromHandler =>
      // Until the timeout occurs, the server must serve the close message
      closed = true
      receiverCliento.foreach { receiverClient =>
        receiverClient ! NotificationToReceiverClientClosed
        context.unwatch(receiverClient)
        receiverCliento = None
        context.setReceiveTimeout(TIMEOUT_CONNECTION)
      }

    case MessagesFromSenderClient(messages) =>
      sockJsActorRefo.foreach { sockJsActorRef =>
        if (!closed) messages.foreach { msg => sockJsActorRef ! SockJsText(msg) }
      }

    case MessageFromHandler(message) =>
      if (!closed) {
        receiverCliento match {
          case None =>
            // Stop if there's no subscriber for a long time
            val now = System.currentTimeMillis()
            if (now - lastSubscribedAt > TIMEOUT_CONNECTION_MILLIS)
              unwatchAndStop()
            else
              bufferForClientSubscriber.append(message)

          case Some(receiverClient) =>
            // buffer is empty at this moment, because receiverCliento is not empty
            receiverClient ! NotificationToReceiverClientMessage(message)
        }
      }

    case ReceiveTimeout =>
      if (closed || receiverCliento.isEmpty) {
        // Closed or no subscriber for a long time
        unwatchAndStop()
      } else {
        // No message for subscriber for a long time
        receiverCliento.get ! NotificationToReceiverClientHeartbeat
      }
  }
}
