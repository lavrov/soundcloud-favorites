import java.io.File
import java.util.concurrent.Executors
import scala.concurrent._, duration._
import scala.Some
import spray.json.{JsonParser, DefaultJsonProtocol}

object Application extends App {

  import DefaultJsonProtocol._
  import dispatch._

  val simultaneousDownloads = 3

  lazy val clientId = args.headOption getOrElse sys.error("Client id isn't set")

  implicit val trackFormat = jsonFormat4(Track)
  implicit val arFormat = arrayFormat[Track]

  val threadPoolExecutor = Executors.newCachedThreadPool()
  implicit val ctx = ExecutionContext fromExecutor threadPoolExecutor

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
    TaskDispatcher(tasks, simultaneousDownloads)
  }

  def doWork() = {
    val dir = new File("downloads")
    if(! dir.exists) dir.mkdir()

    for {
      favorites <- getFavorites
      result    <- downloadTaskList(favorites)
    } yield result
  }

  Await.ready (doWork(), Duration.Inf)

  threadPoolExecutor.shutdown()

  println("Download finished")
}

case class Track(title: String, downloadable: Boolean, download_url: Option[String], original_content_size: Int)

/** *
  * Performs no more than N tasks simultaneously
  */
object TaskDispatcher {
  def apply(tasks: Iterator[Future[Any]], simultaneous: Int) = {
    var available = simultaneous
    val complete = promise[Unit]()
    val singleThreadExecutor = Executors.newSingleThreadExecutor
    implicit val singleThreadCtx = ExecutionContext fromExecutor singleThreadExecutor

    def run(): Unit = {
      if(available > 0)
        if(tasks.hasNext) {
          tasks.next().onComplete(_ => release())
          available -= 1
          run()
        }
        else if(available == simultaneous) {
          complete success ()
          singleThreadExecutor.shutdown()
        }
    }

    def release() = {
      available += 1
      run()
    }

    future { run() }
    complete.future
  }
}
