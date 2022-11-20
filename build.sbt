ThisBuild / tlBaseVersion := "0.1"

ThisBuild / organization := "com.armanbilge"
ThisBuild / organizationName := "Arman Bilge"
ThisBuild / developers += tlGitHubDev("armanbilge", "Arman Bilge")
ThisBuild / startYear := Some(2022)
ThisBuild / tlSonatypeUseLegacyHost := false

ThisBuild / crossScalaVersions := Seq("3.2.1", "2.13.10")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / githubWorkflowOSes := Seq("ubuntu-20.04", "ubuntu-22.04")

ThisBuild / githubWorkflowBuildPreamble ++= {
  val brew = "/home/linuxbrew/.linuxbrew/bin/brew"
  List(
    WorkflowStep.Run(
      List("uname -a")
    ),
    WorkflowStep.Run(
      List(s"$brew update", s"$brew install liburing"),
      name = Some("Install liburing")
    )
  )
}

val fs2Version = "3.4.0"
val munitCEVersion = "2.0.0-M3"

ThisBuild / nativeConfig ~= { c =>
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
