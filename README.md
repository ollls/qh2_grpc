PoC Quartz-H2 and sbt-fs2-grpc plugin.

We use generated models and Marshallers from sbt-fs2-grpc but to call the actual service methods we use scala 3.3 macro.

grpcurl -v -insecure -proto orders.proto -d '{"name" : "John The Cube Jr", "number":101 }' localhost:8443 com.example.protos.Greeter/SayHello

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

Universal grpc router for quartz with scala 3 macro.

```scala
 def run(args: List[String]) = {

    val greeterService: Resource[IO, ServerServiceDefinition] =
      GreeterFs2Grpc.bindServiceResource[IO](new GreeterService)

    val T = greeterService.use { sd =>
      for {
        ctx <- QuartzH2Server.buildSSLContext(
          "TLSv1.3",
          "keystore.jks",
          "password"
        )
        grpcIO <- IO(Router(sd).getIO)
        exitCode <- new QuartzH2Server(
          "localhost",
          8443,
          32000,
          Some(ctx)
        ).start(grpcIO, sync = false)
      } yield (exitCode)
    }

    T
```





