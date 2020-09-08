package sjsonnet

import java.io.{BufferedOutputStream, InputStream, OutputStreamWriter, PrintStream, StringWriter, Writer}
import java.nio.charset.StandardCharsets
import java.nio.file.NoSuchFileException

import sjsonnet.Cli.Config

import scala.collection.mutable
import scala.util.Try

object SjsonnetMain {
  def createParseCache() = collection.mutable.Map[String, fastparse.Parsed[(Expr, Map[String, Int])]]()
  def resolveImport(searchRoots0: Seq[Path], allowedInputs: Option[Set[os.Path]] = None)(wd: Path, str: String) = {
    (wd +: searchRoots0)
      .flatMap(base => os.FilePath(str) match {
        case r: os.SubPath => Some(base.asInstanceOf[OsPath].p / r)
        case r: os.RelPath =>
          if (r.ups > base.segmentCount()) None
          else Some(base.asInstanceOf[OsPath].p / r)
        case a: os.Path => Some(a)
      })
      .filter(p => allowedInputs.fold(true)(_(p)))
      .find(os.exists)
      .flatMap(p => try Some((OsPath(p), os.read(p))) catch{case e: Throwable => None})
  }
  def main(args: Array[String]): Unit = {
    val exitCode = main0(
      args match {
        case Array(s, _*) if s == "-i" || s == "--interactive" => args.tail
        case _ => args
      },
      collection.mutable.Map.empty,
      System.in,
      System.out,
      System.err,
      os.pwd,
      None
    )
    System.exit(exitCode)
  }

  def main0(args: Array[String],
            parseCache: collection.mutable.Map[String, fastparse.Parsed[(Expr, Map[String, Int])]],
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream,
            wd: os.Path,
            allowedInputs: Option[Set[os.Path]] = None,
            importer: Option[(Path, String) => Option[os.Path]] = None): Int = {

    val result = for{
      t <- Cli.groupArgs(args.toList, Cli.genericSignature(wd), Cli.Config()).left.map{
        err => err + "\n" + Cli.help(wd)
      }
      (config0, leftover) = t
      t2 <- {
        leftover match{
          case file :: rest => Right((file, rest))
          case _ => Left("error: Need to pass in a jsonnet file to evaluate\n" + Cli.help(wd))
        }
      }
      (file, rest) = t2
      t3 <- Cli.groupArgs(rest, Cli.genericSignature(wd), config0)
      (config, leftover) = t3
      outputStr <- {
        if (config.interactive){
          Left("error: -i/--interactive must be passed in as the first argument")
        }else if (leftover.nonEmpty) {
          Left("error: Unknown arguments: " + leftover.mkString(" "))
        }else mainConfigured(
          file, config, parseCache, wd, allowedInputs, importer
        )
      }
    } yield outputStr

    result match{
      case Left(err) =>
        if (!err.isEmpty) stderr.println(err)
        1
      case Right(str) =>
        if (!str.isEmpty) stdout.println(str)
        0
    }
  }

  def rendererForConfig(wr: Writer, config: Config) =
    if (config.yamlOut) new PrettyYamlRenderer(wr, indent = config.indent)
    else new Renderer(wr, indent = config.indent)
  def handleWriteFile[T](f: => T): Either[String, T] =
    Try(f).toEither.left.map{
      case e: NoSuchFileException => s"open $f: no such file or directory"
      case e => e.toString
    }

  def writeFile(config: Config, f: os.Path, contents: String): Either[String, Unit] =
    handleWriteFile(os.write.over(f, contents, createFolders = config.createDirs))

  def writeToFile[U](config: Config, f: os.Path, contents: Writer => Either[String, U]): Either[String, Unit] =
    handleWriteFile(os.write.over.outputStream(f, createFolders = config.createDirs)).flatMap { out =>
      try {
        val buf = new BufferedOutputStream(out)
        val wr = new OutputStreamWriter(buf, StandardCharsets.UTF_8)
        val u = contents(wr)
        wr.flush()
        u.map(_ => ())
      } finally out.close()
    }

  def renderNormal(config: Config, interp: Interpreter, path: os.Path, wd: os.Path) = {
    def materialize(wr: Writer) = {
      val renderer = rendererForConfig(wr, config)
      val res = interp.interpret0(os.read(path), OsPath(path), renderer)
      if (config.yamlOut) wr.write('\n')
      res
    }
    config.outputFile match{
      case None => materialize(new StringWriter).map(_.toString)
      case Some(f) => writeToFile(config, os.Path(f, wd), materialize(_)).map(_ => "")
    }
  }

  def isScalar(v: ujson.Value) = !v.isInstanceOf[ujson.Arr] && !v.isInstanceOf[ujson.Obj]

  def mainConfigured(file: String,
                     config: Config,
                     parseCache: collection.mutable.Map[String, fastparse.Parsed[(Expr, Map[String, Int])]],
                     wd: os.Path,
                     allowedInputs: Option[Set[os.Path]] = None,
                     importer: Option[(Path, String) => Option[os.Path]] = None): Either[String, String] = {
    val path = os.Path(file, wd)
    val interp = new Interpreter(
      parseCache,
      config.varBinding,
      config.tlaBinding,
      OsPath(wd),
      importer = importer match{
        case Some(i) => (wd: Path, str: String) => i(wd, str).map(p => (OsPath(p), os.read(p)))
        case None => resolveImport(config.jpaths.map(os.Path(_, wd)).map(OsPath(_)), allowedInputs)
      },
      preserveOrder = config.preserveOrder,
      strict = config.strict
    )

    (config.multi, config.yamlStream) match {
      case (Some(multiPath), _) =>
        interp.interpret(os.read(path), OsPath(path)).flatMap{
          case obj: ujson.Obj =>
            val renderedFiles: Seq[Either[String, os.RelPath]] =
              obj.value.toSeq.map{case (f, v) =>
                for{
                  rendered <- {
                    if (config.expectString) {
                      v match {
                        case ujson.Str(s) => Right(s)
                        case _ => Left("expected string result, got: " + v.getClass)
                      }
                    } else Right(ujson.transform(v, new Renderer(indent = config.indent)).toString)
                  }
                  relPath = os.RelPath(multiPath) / os.RelPath(f)
                  _ <- writeFile(config, wd / relPath, rendered)
                } yield relPath
              }
            renderedFiles.collect{case Left(err) => err} match{
              case Nil =>
                Right[String, String](renderedFiles.collect{case Right(path) => path}.mkString("\n"))
              case errs =>
                Left[String, String]("rendering errors:\n" + errs.mkString("\n"))
            }

          case _ =>
            Left("error: multi mode: top-level should be an object " +
              "whose keys are filenames and values hold the JSON for that file.")
        }
      case (None, true) =>
        // YAML stream

        interp.interpret(os.read(path), OsPath(path)).flatMap {
          case arr: ujson.Arr =>
            val stream = arr.value.toSeq match{
              case Nil => ""
              case Seq(single) =>
                val renderer = rendererForConfig(new StringWriter(), config)
                val suffix = if (isScalar(single)) "\n..." else ""
                single.transform(renderer).toString + suffix
              case multiple =>
                multiple.zipWithIndex
                  .map{
                    case (v, i) =>
                      val renderer = rendererForConfig(new StringWriter(), config)
                      val rendered = v.transform(renderer).toString
                      if (isScalar(v)) "--- " + rendered
                      else if (i != 0) "---\n" + rendered
                      else rendered
                  }
                  .mkString("\n")
            }

            config.outputFile match{
              case None => Right[String, String](stream)
              case Some(f) =>
                os.write(os.Path(f, os.pwd), stream + "\n")
                Right("")
            }

          case _ => renderNormal(config, interp, path, wd)
        }
      case _ => renderNormal(config, interp, path, wd)

    }
  }
}
