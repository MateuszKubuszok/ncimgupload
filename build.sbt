lazy val root = (project in file("."))
  .enablePlugins(NativeImagePlugin)
  .settings(
    name := "ncimgupload",
    version := "0.2.0",
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
      "org.jline"       % "jline"       % "3.26.3",
      "org.scalameta"  %% "munit"       % "1.1.0" % Test
    ),
    fork := true,
    Compile / mainClass := Some("ncimgupload.Main"),
    nativeImageGraalHome := java.nio.file.Paths.get(
      sys.env.getOrElse("GRAALVM_HOME", sys.env.getOrElse("JAVA_HOME", ""))
    ),
    nativeImageOptions := {
      val base = baseDirectory.value
      Seq(
        "--no-fallback",
        "--enable-url-protocols=https",
        "-H:+UnlockExperimentalVMOptions",
        "-H:+ReportExceptionStackTraces",
        "--initialize-at-build-time=scala,geny",
        s"-H:ReflectionConfigurationFiles=${base}/native-image/reflect-config.json",
        s"-H:ResourceConfigurationFiles=${base}/native-image/resource-config.json",
        s"-H:JNIConfigurationFiles=${base}/native-image/jni-config.json",
      )
    },
  )
