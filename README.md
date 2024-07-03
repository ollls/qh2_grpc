PoC Quartz-H2 and sbt-fs2-grpc plugin.

TODO:
Project is under development.

We use generated models and Marshallers from sbt-fs2-grpc but to call the actual service methods we use scala 3.3 macro.


Tests with gcpcurl: UnaryToUnary, UnaryToStream, StreamToUnary, StreamToStream
```
grpcurl -v -insecure -proto orders.proto -d '{"name" : "John The Cube Jr", "number":101 }' localhost:8443 com.example.protos.Greeter/SayHello
grpcurl -v -insecure -proto orders.proto -d '{"name" : "John The Cube Jr", "number":101 }' localhost:8443 com.example.protos.Greeter/LotsOfReplies
grpcurl -v -insecure -proto orders.proto -d '{"name" : "MESSAGE1", "number":101 } {"name" : "MESSAGE2", "number":101 }' localhost:8443 com.example.protos.Greeter/LotsOfGreetings
grpcurl -v -insecure -proto orders.proto -d '{"name" : "John The Cube Jr", "number":101 } {"name" : "George The King", "number":101 }' localhost:8443 com.example.protos.Greeter/BidiHello 
```

Universal grpc router for quartz with scala 3 macro.

To call service method directly with fs2 streaming with IO context we use
* ServerServiceDefinition
* TraitMethodFinder.getAllMethods[GreeterService] which was done with scala3 macro.

```scala
  def run(args: List[String]) = {

    val greeterService: Resource[IO, ServerServiceDefinition] =
      GreeterFs2Grpc.bindServiceResource[IO](new GreeterService)

    val exitCode = greeterService.use { sd =>
      for {
        _ <- IO(QuartzH2Server.setLoggingLevel(Level.DEBUG))
        ctx <- QuartzH2Server.buildSSLContext(
          "TLSv1.3",
          "keystore.jks",
          "password"
        )
        grpcIO <- IO(
          Router[GreeterService](
            service,
            sd,
            TraitMethodFinder.getAllMethods[GreeterService]
          ).getIO
        )
        exitCode <- new QuartzH2Server(
          "localhost",
          8443,
          32000,
          Some(ctx)
        ).start(grpcIO, sync = false)
      } yield (exitCode)
    }
    exitCode
  }
```

REST Style interraction with grpc clients also possible.

```scala

 val R: HttpRouteIO = {
    case req @ POST -> Root / "com.example.protos.Greeter" / "SayHello" =>
      for {
        request <- req.body
        io <- service._sayHello(request, null)

      } yield (Response
        .Ok()
        .trailers(
          Headers(
            "grpc-status" -> "0",
            "grpc-message" -> "ok",
            "content-type" -> "application/grpc"
          )
        )
        .hdr("content-type" -> "application/grpc"))
        .asStream(
          Stream.emits(io.toByteArray)
        )
  }

```





