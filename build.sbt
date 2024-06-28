ThisBuild / organization := "com.example"
ThisBuild / scalaVersion := "3.3.3"

Runtime / unmanagedClasspath += baseDirectory.value / "src" / "main" / "resources"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case _                                   => MergeStrategy.first
}

assembly / assemblyJarName := "qh2-http-run.jar"

enablePlugins(Fs2Grpc)

lazy val root = (project in file(".")).settings(
  name := "json-template-qh2",
  libraryDependencies ++= Seq(
    "io.github.ollls" %% "quartz-h2" % "0.8.5",
  )
)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all"
)
