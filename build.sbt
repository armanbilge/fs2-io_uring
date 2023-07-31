ThisBuild / tlBaseVersion := "0.2"

ThisBuild / organization := "com.armanbilge"
ThisBuild / organizationName := "Arman Bilge"
ThisBuild / developers += tlGitHubDev("armanbilge", "Arman Bilge")
ThisBuild / startYear := Some(2022)
ThisBuild / tlSonatypeUseLegacyHost := false

ThisBuild / crossScalaVersions := Seq("3.3.0", "2.13.11")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))

ThisBuild / githubWorkflowBuild ~= { steps =>
  steps.flatMap {
    case step @ WorkflowStep.Sbt(List("Test/nativeLink"), _, _, _, _, _) =>
      List(WorkflowStep.Sbt(List("compile"), name = Some("Compile")))
    case step @ WorkflowStep.Sbt(List("test"), _, _, _, _, _) => Nil
    case step                                                 => List(step)
  }
}

ThisBuild / githubWorkflowBuild +=
  WorkflowStep.Run(
    List("clang-format --dry-run --Werror uring/src/main/resources/scala-native/*.c"),
    name = Some("Check formatting of C sources")
  )

ThisBuild / githubWorkflowPublishPreamble +=
  WorkflowStep.Use(
    UseRef.Public("typelevel", "await-cirrus", "main"),
    name = Some("Wait for Cirrus CI")
  )

val fs2Version = "3.8.0"
val munitCEVersion = "2.0.0-M3"

ThisBuild / nativeConfig ~= { c =>
  if (Option(System.getenv("CI")).contains("true"))
    c.withLinkingOptions(c.linkingOptions :+ "-luring")
  else
    c.withCompileOptions(c.compileOptions :+ "-I/home/linuxbrew/.linuxbrew/include")
      .withLinkingOptions(c.linkingOptions :+ "/home/linuxbrew/.linuxbrew/lib/liburing.a")
}

lazy val root = tlCrossRootProject.aggregate(uring)

lazy val uring = project
  .in(file("uring"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "fs2-io_uring",
    libraryDependencies ++= Seq(
      "co.fs2" %%% "fs2-io" % fs2Version,
      "org.typelevel" %%% "munit-cats-effect" % munitCEVersion % Test
    ),
    Test / testOptions += Tests.Argument("+l")
  )
