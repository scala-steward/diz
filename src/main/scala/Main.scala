import discord4j.core.`object`.entity.Message
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.event.domain.lifecycle.ReadyEvent
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.{
  DiscordClient,
  DiscordClientBuilder,
  GatewayDiscordClient
}
import io.github.bbarker.diz.data.*
import reactor.core.publisher.{Flux, Mono}
import zio.Console.*
import zio.Exit.Success
import zio.*

object EnvVars:
  val discordToken = "DISCORD_TOKEN"

object Diz extends zio.App:
  def run(args: List[String]) =
    mainLogic
      .catchAll(err => putStrLn(s"Error: $err"))
      .catchAllDefect(err => putStrLn(s"Defect: $err"))
      .exitCode

  val mainLogic: ZIO[Console, Throwable, Unit] =
    (for {
      _ <- putStrLn("Starting DiZ bot")
      discordToken <- ZIO
        .fromOption(sys.env.get(EnvVars.discordToken))
        .mapError(err =>
          new RuntimeException(s"${EnvVars.discordToken} not set")
        )
      client <- UIO(DiscordClientBuilder.create(discordToken).build())
      gateway <- ZIO.effect(client.login.block())
      userMessages = getUserMessages(gateway)

      _ <- randomlySayQuote(userMessages, maxQuoteRoll = 8)
      _ <- pingPong(userMessages)
      _ <- ZIO.effect(gateway.onDisconnect().block())

    } yield ()).provideSomeLayer(DizQuotes.layer ++ Random.live)

  def pingPong(userMessages: Flux[Message]): Task[Unit] = ZIO.effect(
    userMessages
      .filter(message => message.getContent().equalsIgnoreCase("!ping"))
      .flatMap(_.getChannel)
      .flatMap(channel => channel.createMessage("Pong!"))
      .subscribe()
  )

  /** Will only say a quote when the the max quote roll is rolled
    */
  def randomlySayQuote(
      userMessages: Flux[Message],
      maxQuoteRoll: Int
  ): ZIO[Quotes & Random & Console, Throwable, Unit] = for {
    // TODO: need to integrate Flux and ZIO, see #1
    // roll <- Random.nextIntBetween(1, maxQuoteRoll + 1)
    quotes <- ZIO.service[Quotes]
    _ <- ZIO.effect(
      userMessages
        .flatMap(message => {
          val roll = Runtime.default
            .unsafeRunSync(Random.nextIntBetween(1, maxQuoteRoll + 1))
          roll match
            case Success(maxQuoteRoll) =>
              val bestQuote =
                quotes.sayQuote(quotes.findBestQuote(message.getContent()))
              bestQuote match
                case Some(quote) =>
                  message.getChannel.flatMap(_.createMessage(quote))
                case None => Mono.empty
            case _ => Mono.empty
        })
        .subscribe()
    )

  } yield ()

  def getUserMessages(gateway: GatewayDiscordClient): Flux[Message] = gateway
    .getEventDispatcher()
    .on(classOf[MessageCreateEvent])
    .map(_.getMessage)
    .filter(message =>
      message.getAuthor().map(user => !user.isBot()).orElse(false)
    )
