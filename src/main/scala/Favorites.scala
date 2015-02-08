package com.github.lavrov.soundcloud_favorites

import java.io.{File, FileOutputStream}

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import play.api.libs.iteratee.Iteratee
import play.api.libs.json._
import play.api.libs.ws._
import play.api.libs.ws.ning.{NingAsyncHttpClientConfigBuilder, NingWSClient}

import scala.concurrent._

class Favorites(clientId: String, username: String = "lavrovvitaliy", simultaneousDownloads: Int = 3)(implicit system: ActorSystem) {
  import system.dispatcher

  implicit val trackFormat = Json.reads[Track]

  implicit val wsClient = new NingWSClient(new NingAsyncHttpClientConfigBuilder(new DefaultWSClientConfig).build())

  def getFavorites =
    for { response <-
      WS.clientUrl(s"http://api.soundcloud.com/users/$username/favorites.json?client_id=$clientId").get() }
    yield
      Json.fromJson[List[Track]](response.json).get


  def downloadTrack(title: String, downloadUrl: String) = {
    val fullUrl = s"$downloadUrl?client_id=$clientId"
    WS.clientUrl(fullUrl).withFollowRedirects(true).getStream().flatMap {
      case (_, enumerator) =>
        println(s"downloading $title")
        val filename = s"downloads/${title replace ('/', ' ')}.mp3"
        val fileStream = new FileOutputStream(filename)
        enumerator.onDoneEnumerating(fileStream.close())
        enumerator run Iteratee.foreach(fileStream.write) map { _ =>
          println(s"complete $title")
        }
    }
  }

  def downloadTaskList(tracks: List[Track]): Future[Int] = {
    import akka.stream._
    implicit val materializer = ActorFlowMaterializer(
      ActorFlowMaterializerSettings(system).withInputBuffer(1, 1)
    )

    val source = Source(tracks).collect {
      case Track(title, downloadUrl, Some(streamUrl)) =>
        (title, downloadUrl getOrElse streamUrl)
    }

    val sink = Sink.fold[Int, Unit](0)((c, _) => c + 1)

    val graph =
      FlowGraph { implicit builder =>
        import FlowGraphImplicits._

        val balance = Balance[(String, String)]
        val merge = Merge[Unit]
        val flow = Flow[(String, String)] mapAsync (downloadTrack _).tupled

        source ~> balance
        merge ~> sink

        (1 to simultaneousDownloads) foreach { _ =>
          balance ~> flow ~> merge
        }

      }

      graph.runWith(sink)
  }

  def download() = {
    val dir = new File("downloads")
    if(! dir.exists) dir.mkdir()

    for {
      favorites <- getFavorites
      count    <- downloadTaskList(favorites)
    } yield
      println(s"$count tracks downloaded")
  }

}

case class Track(title: String, download_url: Option[String], stream_url: Option[String])
