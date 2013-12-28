import java.io.File
import java.util.concurrent.Executors
import scala.concurrent._, duration._
import play.api.libs.json._

object Application extends App {

  import dispatch._

  val simultaneousDownloads = args.lift(1).map(_.toInt) getOrElse 3

  lazy val clientId = args.headOption getOrElse sys.error("Client id isn't set")

  implicit val trackFormat = Json.reads[Track]

  val threadPoolExecutor = Executors.newCachedThreadPool()
  implicit val ctx = ExecutionContext fromExecutor threadPoolExecutor

  def getFavorites = Http {
    url(s"http://api.soundcloud.com/users/lavrovvitaliy/favorites.json?client_id=$clientId") OK
    as.String
  } map parseResponse

  def parseResponse(body: String) = {
    val jsValue = Json parse body
    Json.fromJson[List[Track]](jsValue).get
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
      case Track(title, downloadUrl, Some(streamUrl)) =>
        downloadTrack(title, downloadUrl getOrElse streamUrl)
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

case class Track(title: String, download_url: Option[String], stream_url: Option[String])

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
