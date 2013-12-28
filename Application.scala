import akka.actor.{ActorSystem, Props, Actor}
import java.io.File
import java.util.concurrent.Executors
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
    val tasks = tracks.toIterator.collect {
      case Track(title, true, Some(url), _) =>
        downloadTrack(title, url)
    }
    executeTasks(tasks, simultaneousDownloads)
  }

  def executeTasks(tasks: Iterator[Future[Any]], simultaneous: Int) = {
    var available = simultaneous
    val complete = promise[Unit]()
    val singleThreadCtx = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor)

    def run(): Unit = {
      if(available > 0)
        if(tasks.hasNext) {
          tasks.next().onComplete(_ => release())(singleThreadCtx)
          available -= 1
          run()
        }
        else if(available == simultaneous)
          complete success ()
    }

    def release() = {
      available += 1
      run()
    }

    future { run() }(singleThreadCtx)
    complete.future
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
