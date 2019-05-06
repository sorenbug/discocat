package org.discordscala.discocat

import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import fs2.{io => _, _}
import fs2.concurrent.{Queue, Topic}
import io.circe.DecodingFailure
import org.discordscala.discocat.ws.event.HeartbeatAck
import org.discordscala.discocat.ws.{Event, EventDecoder, EventStruct, Socket}
import spinoco.fs2.http.HttpClient
import spinoco.fs2.http.websocket.WebSocketRequest
import spinoco.protocol.http.Uri
import spire.math.ULong

case class Client[F[_]](
  token: String,
  httpClient: HttpClient[F],
  deferredSocket: Deferred[F, Socket[F]],
  decoder: EventDecoder = Defaults.defaultEventDecoder,
  apiRoot: Uri = Uri.https("discordapp.com", "/api/v6/"),
  gatewayRoot: Uri = Uri.wss("gateway.discord.gg", "/?v=6&encoding=json"),
) {

  def decode(e: EventStruct): Option[Either[DecodingFailure, Event[F]]] = decoder.decode(this).lift(e)

  def login(handlers: EventHandlers[F])(implicit concurrent: Concurrent[F]): F[Unit] =
    (for {
      client <- Stream(httpClient)
      req = WebSocketRequest.wss(
        gatewayRoot.host.host,
        gatewayRoot.host.port.getOrElse(443),
        gatewayRoot.path.stringify,
        gatewayRoot.query.params: _*
      )
      inbound <- Stream.eval(Topic[F, Event[F]](HeartbeatAck(this)))
      outbound <- Stream.eval(Queue.unbounded[F, Event[F]])
      ref <- Stream.eval(Ref.of[F, Option[ULong]](None))
      sock = Socket(this, handlers, inbound, outbound, ref)
      _ <- Stream.eval(deferredSocket.complete(sock))
      o <- client.websocket(req, sock.pipe)(scodec.codecs.utf8, scodec.codecs.utf8)
    } yield o).compile.drain

}
