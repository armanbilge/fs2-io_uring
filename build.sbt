ThisBuild / tlBaseVersion := "0.3"

ThisBuild / organization := "com.armanbilge"
ThisBuild / organizationName := "Arman Bilge"
ThisBuild / developers += tlGitHubDev("armanbilge", "Arman Bilge")
ThisBuild / startYear := Some(2022)

ThisBuild / crossScalaVersions := Seq("3.3.3", "2.13.14")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))

ThisBuild / githubWorkflowBuild ~= { steps =>
  steps.flatMap {
    case step: WorkflowStep.Sbt if step.commands == List("Test/nativeLink") =>
      List(WorkflowStep.Sbt(List("compile"), name = Some("Compile")))
    case step: WorkflowStep.Sbt if step.commands == List("test") => Nil
    case step                                                    => List(step)
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

val ceVersion = "3.6-e9aeb8c"
val fs2Version = "3.11.0"
val munitCEVersion = "2.0.0"

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
      "org.typelevel" %%% "cats-effect" % ceVersion,
      "co.fs2" %%% "fs2-io" % fs2Version,
      "org.typelevel" %%% "munit-cats-effect" % munitCEVersion % Test
    ),
    Test / testOptions += Tests.Argument("+l")
  )
