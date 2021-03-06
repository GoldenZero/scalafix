package scalafix.sbt

import scala.language.reflectiveCalls

import scalafix.Versions
import sbt.File
import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin
import scalafix.internal.sbt.ScalafixCompletions
import scalafix.internal.sbt.ScalafixJarFetcher
import org.scalameta.BuildInfo
import sbt.Def

object ScalafixPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin
  object autoImport {
    val scalafix: InputKey[Unit] =
      inputKey[Unit]("Run scalafix rule.")
    val scalafixTest: InputKey[Unit] =
      inputKey[Unit]("Run scalafix as a test(without modifying sources).")
    val sbtfix: InputKey[Unit] =
      inputKey[Unit](
        "Run scalafix rule on build sources. Requires the semanticdb-sbt plugin to be enabled globally.")
    val sbtfixTest: InputKey[Unit] =
      inputKey[Unit](
        "Run scalafix rule on build sources as a test(without modifying sources).")
    val scalafixConfig: SettingKey[Option[File]] =
      settingKey[Option[File]](
        ".scalafix.conf file to specify which scalafix rules should run.")
    val scalafixSourceroot: SettingKey[File] = settingKey[File](
      s"Which sourceroot should be used for .semanticdb files.")
    val scalafixVersion: SettingKey[String] = settingKey[String](
      s"Which scalafix version to run. Default is ${Versions.version}.")
    val scalafixScalaVersion: SettingKey[String] = settingKey[String](
      s"Which scala version to run scalafix from. Default is ${Versions.scala212}.")
    val scalafixSemanticdbVersion: SettingKey[String] = settingKey[String](
      s"Which version of semanticdb to use. Default is ${Versions.scalameta}.")
    val scalafixVerbose: SettingKey[Boolean] =
      settingKey[Boolean]("pass --verbose to scalafix")

    def scalafixConfigure(configs: Configuration*): Seq[Setting[_]] =
      List(
        configureForConfigurations(
          configs,
          scalafix,
          c => scalafixTaskImpl(c, Nil)),
        configureForConfigurations(
          configs,
          scalafixTest,
          c => scalafixTaskImpl(c, Seq("--test")))
      ).flatten

    /** Add -Yrangepos and semanticdb sourceroot to scalacOptions. */
    def scalafixScalacOptions: Def.Initialize[Seq[String]] =
      ScalafixPlugin.scalafixScalacOptions

    /** Add semanticdb-scalac compiler plugin to libraryDependencies. */
    def scalafixLibraryDependencies: Def.Initialize[List[ModuleID]] =
      ScalafixPlugin.scalafixLibraryDependencies

    /** Enable semanticdb-sbt for all projects with id *-build. */
    def sbtfixSettings: Seq[Def.Setting[_]] =
      ScalafixPlugin.sbtfixSettings

    /** Settings that must appear after scalacOptions and libraryDependencies */
    def scalafixSettings: Seq[Def.Setting[_]] = List(
      scalacOptions ++= scalafixScalacOptions.value,
      libraryDependencies ++= scalafixLibraryDependencies.value
    )

    // TODO(olafur) remove this in 0.6.0, replaced
    val scalafixEnabled: SettingKey[Boolean] =
      settingKey[Boolean](
        "No longer used. Use the scalafixEnable command or manually configure " +
          "scalacOptions/libraryDependecies/scalaVersion")
    @deprecated("Renamed to scalafixSourceroot", "0.5.0")
    val scalametaSourceroot: SettingKey[File] = scalafixSourceroot
  }
  import scalafix.internal.sbt.CliWrapperPlugin.autoImport._
  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    scalafixSettings ++ // TODO(olafur) remove this line in 0.6.0
      scalafixTaskSettings ++
      scalafixTestTaskSettings
  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    scalafixConfig := Option(file(".scalafix.conf")).filter(_.isFile),
    cliWrapperMainClass := "scalafix.cli.Cli$",
    scalafixEnabled := true,
    scalafixVerbose := false,
    commands += ScalafixEnable.command,
    sbtfix := sbtfixImpl().evaluated,
    sbtfixTest := sbtfixImpl(Seq("--test")).evaluated,
    aggregate.in(sbtfix) := false,
    aggregate.in(sbtfixTest) := false,
    scalafixSourceroot := baseDirectory.in(ThisBuild).value,
    scalafixVersion := Versions.version,
    scalafixSemanticdbVersion := Versions.scalameta,
    scalafixScalaVersion := Versions.scala212,
    cliWrapperClasspath := {
      val jars = ScalafixJarFetcher.fetchJars(
        "ch.epfl.scala",
        s"scalafix-cli_${scalafixScalaVersion.value}",
        scalafixVersion.value
      )
      if (jars.isEmpty) {
        throw new MessageOnlyException("Unable to download scalafix-cli jars!")
      }
      jars
    }
  )

  private def sbtfixImpl(extraOptions: Seq[String] = Seq()) = {
    Def.inputTaskDyn {
      // Will currently fail silently if semanticdb-sbt is not enabled.
      // See https://github.com/scalacenter/scalafix/issues/264
      val baseDir = baseDirectory.in(ThisBuild).value
      val sbtDir: File = baseDir./("project")
      val sbtFiles = baseDir.*("*.sbt").get
      val options =
        "--no-strict-semanticdb" ::
          "--classpath-auto-roots" ::
          baseDir./("target").getAbsolutePath ::
          sbtDir.getAbsolutePath ::
          Nil ++ extraOptions
      scalafixTaskImpl(
        scalafixParser.parsed,
        options,
        sbtDir +: sbtFiles,
        "sbt-build",
        streams.value
      )
    }
  }

  // hack to avoid illegal dynamic reference, can't figure out how to do
  // scalafixParser(baseDirectory.in(ThisBuild).value).parsed
  private def workingDirectory = file(sys.props("user.dir"))
  private val scalafixParser =
    ScalafixCompletions.parser(workingDirectory.toPath)
  private object logger {
    def warn(msg: String): Unit = {
      println(
        s"[${scala.Console.YELLOW}warn${scala.Console.RESET}] scalafix - $msg")
    }
  }
  private val isSupportedScalaVersion = Def.setting {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 11 | 12)) => true
      case _ => false
    }
  }

  lazy val scalafixLibraryDependencies: Def.Initialize[List[ModuleID]] =
    Def.setting {
      if (scalafixEnabled.value && isSupportedScalaVersion.value) {
        compilerPlugin(
          "org.scalameta" % "semanticdb-scalac" % scalafixSemanticdbVersion.value cross CrossVersion.full
        ) :: Nil
      } else Nil
    }
  lazy val scalafixScalacOptions: Def.Initialize[Seq[String]] = Def.setting {
    if (scalafixEnabled.value && isSupportedScalaVersion.value) {
      Seq(
        "-Yrangepos",
        s"-Xplugin-require:semanticdb",
        s"-P:semanticdb:sourceroot:${scalafixSourceroot.value.getAbsolutePath}"
      )
    } else Nil
  }

  lazy val sbtfixSettings: Seq[Def.Setting[_]] = Def.settings(
    libraryDependencies ++= {
      val sbthost = "org.scalameta" % "semanticdb-sbt" % Versions.semanticdbSbt cross CrossVersion.full
      val isMetabuild = {
        val p = thisProject.value
        p.id.endsWith("-build") && p.base.getName == "project"
      }
      if (isMetabuild) compilerPlugin(sbthost) :: Nil
      else Nil
    }
  )

  lazy val scalafixTaskSettings: Seq[Def.Setting[InputTask[Unit]]] =
    configureForConfigurations(
      List(Compile, Test),
      scalafix,
      c => scalafixTaskImpl(c))
  lazy val scalafixTestTaskSettings: Seq[Def.Setting[InputTask[Unit]]] =
    configureForConfigurations(
      List(Compile, Test),
      scalafixTest,
      c => scalafixTaskImpl(c, Seq("--test")))

  @deprecated(
    "Use configureForConfiguration(List(Compile, Test), ...) instead",
    "0.5.4")
  def configureForCompileAndTest(
      task: InputKey[Unit],
      impl: Seq[Configuration] => Def.Initialize[InputTask[Unit]]
  ): Seq[Def.Setting[InputTask[Unit]]] =
    configureForConfigurations(List(Compile, Test), task, impl)

  /** Configure scalafix/scalafixTest tasks for given configurations */
  def configureForConfigurations(
      configurations: Seq[Configuration],
      task: InputKey[Unit],
      impl: Seq[Configuration] => Def.Initialize[InputTask[Unit]]
  ): Seq[Def.Setting[InputTask[Unit]]] =
    (task := impl(configurations).evaluated) +:
      configurations.map(c => task.in(c) := impl(Seq(c)).evaluated)

  def scalafixTaskImpl(
      config: Seq[Configuration],
      extraOptions: Seq[String] = Seq()): Def.Initialize[InputTask[Unit]] =
    Def.inputTaskDyn {
      scalafixTaskImpl(
        scalafixParser.parsed,
        ScopeFilter(configurations = inConfigurations(config: _*)),
        extraOptions)
    }

  def scalafixTaskImpl(
      inputArgs: Seq[String],
      filter: ScopeFilter,
      extraOptions: Seq[String]): Def.Initialize[Task[Unit]] =
    Def.taskDyn {
      compile.all(filter).value // trigger compilation
      val classpath = classDirectory.all(filter).value.asPath
      val directoriesToFix: Seq[File] =
        unmanagedSourceDirectories.all(filter).value.flatten.collect {
          case p if p.exists() => p.getAbsoluteFile
        }
      val options: Seq[String] = List("--classpath", classpath) ++ extraOptions
      scalafixTaskImpl(
        inputArgs,
        options,
        directoriesToFix,
        thisProject.value.id,
        streams.value
      )
    }

  def scalafixTaskImpl(
      inputArgs: Seq[String],
      options: Seq[String],
      files: Seq[File],
      projectId: String,
      streams: TaskStreams
  ): Def.Initialize[Task[Unit]] = {
    if (files.isEmpty) Def.task(())
    else {
      Def.task {
        val log = streams.log
        val verbose = if (scalafixVerbose.value) "--verbose" :: Nil else Nil
        val main = cliWrapperMain.value
        val baseArgs = Set[String](
          "--project-id",
          projectId,
          "--no-sys-exit",
          "--non-interactive"
        )
        val args: Seq[String] = {
          // run scalafix rules
          val config =
            scalafixConfig.value
              .map(x => "--config" :: x.getAbsolutePath :: Nil)
              .getOrElse(Nil)
          val ruleArgs =
            if (inputArgs.nonEmpty)
              inputArgs.flatMap("-r" :: _ :: Nil)
            else Nil
          val sourceroot = scalafixSourceroot.value.getAbsolutePath
          // only fix unmanaged sources, skip code generated files.
          verbose ++
            config ++
            ruleArgs ++
            baseArgs ++
            options ++
            List(
              "--sourceroot",
              sourceroot
            )
        }
        val finalArgs = args ++ files.map(_.getAbsolutePath)
        val nonBaseArgs = finalArgs.filterNot(baseArgs).mkString(" ")
        log.info(s"Running scalafix $nonBaseArgs")
        main.main(finalArgs.toArray)
      }
    }
  }

  private[scalafix] implicit class XtensionFormatClasspath(paths: Seq[File]) {
    def asPath: String =
      paths.toIterator
        .collect { case f if f.exists() => f.getAbsolutePath }
        .mkString(java.io.File.pathSeparator)
  }
}
