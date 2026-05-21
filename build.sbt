lazy val root = (project in file("."))
  .settings(
    name := "nkupload",
    version := "0.1.0",
    scalaVersion := "3.3.6",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies ++= Seq(
      "com.lihaoyi"    %% "mainargs"    % "0.7.6",
      "com.lihaoyi"    %% "os-lib"      % "0.11.4",
      "com.lihaoyi"    %% "requests"    % "0.9.0",
      "com.lihaoyi"    %% "upickle"     % "4.1.0",
      "com.typesafe"    % "config"      % "1.4.3",
      "org.xerial"      % "sqlite-jdbc" % "3.47.2.0",
      "org.scala-lang.modules" %% "scala-xml" % "2.3.0",
      "org.scalameta"  %% "munit"       % "1.1.0" % Test
    ),
    fork := true
  )
