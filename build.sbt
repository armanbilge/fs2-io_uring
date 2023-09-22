ThisBuild / tlBaseVersion := "0.3"

ThisBuild / organization := "com.armanbilge"
ThisBuild / organizationName := "Arman Bilge"
ThisBuild / developers += tlGitHubDev("armanbilge", "Arman Bilge")
ThisBuild / startYear := Some(2022)
ThisBuild / tlSonatypeUseLegacyHost := false

ThisBuild / crossScalaVersions := Seq("3.3.0", "2.13.11")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))

ThisBuild / githubWorkflowBuild ~= { steps =>
  steps.flatMap {
    case step @ WorkflowStep.Sbt(List("Test/nativeLink"), _, _, _, _, _, _, _) => Nil
    case step @ WorkflowStep.Sbt(List("test"), _, _, _, _, _, _, _) =>
      List(step.copy(commands = List("Test/compile"), name = Some("Compile")))
    case step => List(step)
  }
}

ThisBuild / githubWorkflowBuild +=
  WorkflowStep.Run(
    List("clang-format --dry-run --Werror uring/native/src/main/resources/scala-native/*.c"),
    name = Some("Check formatting of C sources"),
    cond = Some("matrix.project == 'rootNative'")
  )

ThisBuild / githubWorkflowPublishPreamble +=
  WorkflowStep.Use(
    UseRef.Public("typelevel", "await-cirrus", "main"),
    name = Some("Wait for Cirrus CI")
  )

val ceVersion = "3.6-e9aeb8c"
val fs2Version = "3.8.0"
val nettyVersion = "0.0.22.Final"
val munitCEVersion = "2.0.0-M3"

lazy val classifier = System.getProperty("os.arch") match {
  case "amd64"   => "linux-x86_64"
  case "aarch64" => "linux-aarch_64"
}

ThisBuild / nativeConfig ~= { c =>
  if (Option(System.getenv("CI")).contains("true"))
    c.withLinkingOptions(c.linkingOptions :+ "-luring")
  else
    c.withCompileOptions(c.compileOptions :+ "-I/home/linuxbrew/.linuxbrew/include")
      .withLinkingOptions(c.linkingOptions :+ "/home/linuxbrew/.linuxbrew/lib/liburing.a")
}

lazy val root = tlCrossRootProject.aggregate(uring)

lazy val uring = crossProject(NativePlatform, JVMPlatform)
  .in(file("uring"))
  .settings(
    name := "fs2-io_uring",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % ceVersion,
      "co.fs2" %%% "fs2-io" % fs2Version,
      "org.typelevel" %%% "munit-cats-effect" % munitCEVersion % Test
    ),
    Test / testOptions += Tests.Argument("+l")
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "io.netty.incubator" % "netty-incubator-transport-classes-io_uring" % nettyVersion,
      ("io.netty.incubator" % "netty-incubator-transport-native-io_uring" % nettyVersion % Test)
        .classifier(classifier)
    ),
    fork := true
  )