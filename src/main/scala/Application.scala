import akka.actor.ActorSystem
import com.github.lavrov.soundcloud_favorites._
import scala.concurrent._
import scala.concurrent.duration._


object Application {

  def main(args: Array[String]) = {
    println("Started")

    implicit val system = ActorSystem("favorites")

    val clientId = (
      args.headOption
        orElse sys.props.get("clientId")
        getOrElse sys.error("Client id isn't set")
      )

    val username = sys.props.get("username")

    Await.result(new Favorites(clientId, username getOrElse "lavrovvitaliy").download(), Duration.Inf)

    system.shutdown()
    system.awaitTermination()

    println("Finished")
  }
}