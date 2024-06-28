PoC Quartz-H2 and sbt-fs2-grpc plugin.

We use generated models and Marshallers from sbt-fs2-grpc but to call the actual service methods we use scala 3.3 macro.

grpcurl -v -insecure -proto orders.proto -d '{"name" : "John The Cube Jr", "number":101 }' localhost:8443 com.example.protos.Greeter/SayHello
