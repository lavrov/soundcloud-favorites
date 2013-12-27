import akka.actor.{ActorSystem, Props, Actor}
import java.io.File
import scala.concurrent._, duration._
import scala.Some
import spray.json.{JsonParser, DefaultJsonProtocol}

object Application extends App {

  import DefaultJsonProtocol._

  val simultaneousDownloads = 3

  lazy val clientId = args.headOption getOrElse sys.error("Client id isn't set")

  implicit val trackFormat = jsonFormat4(Track)
  implicit val arFormat = arrayFormat[Track]

  import dispatch._, Defaults._

  def getFavorites = Http {
    url(s"http://api.soundcloud.com/users/lavrovvitaliy/favorites.json?client_id=$clientId") OK
    as.String
  } map parseResponse

  def parseResponse(body: String) = {
    val tracksJson = JsonParser(body)
    tracksJson.convertTo[Array[Track]]
  }

  def downloadTrack(title: String, downloadUrl: String) = {
    val fullUrl = s"$downloadUrl?client_id=$clientId"
    println(s"download $fullUrl, title $title")
    Http(
      url(fullUrl).setFollowRedirects(true) > as.File(new File(s"downloads/$title.mp3"))
    )
  }

  def downloadTaskList(tracks: Seq[Track]) = {
    val tasks =
      tracks.toStream.collect { case Track(title, true, Some(url), size) if size < 10000000 =>
        downloadTrack(title, url)
      }
    val completePromise = promise[Unit]()
    val system = ActorSystem()
    system.actorOf(Props(new TaskDispatcher(tasks, simultaneousDownloads, completePromise)))
    completePromise.future
  }

  def doWork() = {

    for {
      favorites <- getFavorites
      result <- downloadTaskList(favorites)
    } yield result
  }
  Await.ready (doWork(), Duration.Inf)
}

case class Track(title: String, downloadable: Boolean, download_url: Option[String], original_content_size: Int)

case object Done

class TaskDispatcher(tasks: Stream[Future[Any]], simultaneous: Int, complete: Promise[Unit])(implicit ctx: ExecutionContext) extends Actor {
  private val taskIterator = tasks.iterator
  private var released = 0

  1 to simultaneous foreach (_ => self ! Done)

  private def pullNext() =
    if(taskIterator.hasNext)
      taskIterator.next().onComplete(_ => self ! Done)
    else {
      released += 1
      if(released == simultaneous)
        complete success ()
    }

  def receive = {
    case Done =>
      pullNext()
  }
}