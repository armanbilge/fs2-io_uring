ThisBuild / tlBaseVersion := "0.2"

ThisBuild / organization := "com.armanbilge"
ThisBuild / organizationName := "Arman Bilge"
ThisBuild / developers += tlGitHubDev("armanbilge", "Arman Bilge")
ThisBuild / startYear := Some(2022)
ThisBuild / tlSonatypeUseLegacyHost := false

ThisBuild / crossScalaVersions := Seq("3.2.2", "2.13.10")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / githubWorkflowOSes := Seq("ubuntu-20.04", "ubuntu-22.04")

ThisBuild / githubWorkflowBuildPreamble ++= {
  val brew = "/home/linuxbrew/.linuxbrew/bin/brew"
  List(
    WorkflowStep.Run(
      List("uname -a")
    ),
    WorkflowStep.Run(
      List(s"$brew install liburing"),
      name = Some("Install liburing"),
      cond = Some("matrix.project == 'rootNative'")
    )
  )
}

ThisBuild / githubWorkflowBuild +=
  WorkflowStep.Run(
    List("clang-format --dry-run --Werror uring/src/main/resources/scala-native/*.c"),
    name = Some("Check formatting of C sources")
  )

val ceVersion = "3.6-0142603"
val fs2Version = "3.7.0"
val munitCEVersion = "2.0.0-M3"

ThisBuild / nativeConfig ~= { c =>
  val arch = System.getProperty("os.arch").toLowerCase()
  if (Set("arm64", "aarch64").contains(arch))
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
