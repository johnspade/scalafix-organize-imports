package fix

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.meta.Import
import scala.meta.Importee
import scala.meta.Importer
import scala.meta.Pkg
import scala.meta.Source
import scala.meta.Stat
import scala.meta.Term
import scala.meta.Tree
import scala.util.matching.Regex

import metaconfig._
import metaconfig.generic.Surface
import metaconfig.generic.deriveDecoder
import metaconfig.generic.deriveSurface
import scalafix.internal.config.ReaderUtil
import scalafix.patch.Patch
import scalafix.v1._

sealed trait ImportSelectorsOrder

object ImportSelectorsOrder {
  case object Ascii extends ImportSelectorsOrder
  case object SymbolsFirst extends ImportSelectorsOrder
  case object Keep extends ImportSelectorsOrder

  implicit def reader: ConfDecoder[ImportSelectorsOrder] = ReaderUtil.fromMap {
    List(Ascii, SymbolsFirst, Keep) groupBy (_.toString) mapValues (_.head)
  }
}

sealed trait GroupedImports

object GroupedImports {
  case object Merge extends GroupedImports
  case object Explode extends GroupedImports
  case object Keep extends GroupedImports

  implicit def reader: ConfDecoder[GroupedImports] = ReaderUtil.fromMap {
    List(Merge, Explode, Keep) groupBy (_.toString) mapValues (_.head)
  }
}

final case class OrganizeImportsConfig(
  importSelectorsOrder: ImportSelectorsOrder = ImportSelectorsOrder.Ascii,
  groupedImports: GroupedImports = GroupedImports.Explode,
  groups: Seq[String] = Seq("re:javax?\\.", "scala.", "*")
)

object OrganizeImportsConfig {
  val default: OrganizeImportsConfig = OrganizeImportsConfig()

  implicit val surface: Surface[OrganizeImportsConfig] =
    deriveSurface[OrganizeImportsConfig]

  implicit val decoder: ConfDecoder[OrganizeImportsConfig] =
    deriveDecoder[OrganizeImportsConfig](default)
}

sealed trait ImportMatcher {
  def matches(i: Importer): Boolean
}

case class RegexMatcher(pattern: Regex) extends ImportMatcher {
  override def matches(i: Importer): Boolean = (pattern findPrefixMatchOf i.syntax).nonEmpty
}

case class PlainTextMatcher(pattern: String) extends ImportMatcher {
  override def matches(i: Importer): Boolean = i.syntax startsWith pattern
}

case object WildcardMatcher extends ImportMatcher {
  // This matcher should not match anything. The wildcard group is always special-cased at the end
  // of the import group matching process.
  def matches(importer: Importer): Boolean = false
}

class OrganizeImports(config: OrganizeImportsConfig) extends SemanticRule("OrganizeImports") {
  import OrganizeImports._

  private val importMatchers = {
    val matchers = config.groups map {
      case p if p startsWith "re:" => RegexMatcher(new Regex(p stripPrefix "re:"))
      case "*"                     => WildcardMatcher
      case p                       => PlainTextMatcher(p)
    }

    // The wildcard group should always exist. Append one at the end if omitted.
    if (matchers contains WildcardMatcher) matchers else matchers :+ WildcardMatcher
  }

  private val wildcardGroupIndex = importMatchers indexOf WildcardMatcher

  def this() = this(OrganizeImportsConfig())

  override def isExperimental: Boolean = true

  override def withConfiguration(config: Configuration): Configured[Rule] = {
    config.conf.getOrElse("OrganizeImports")(OrganizeImportsConfig()).map(new OrganizeImports(_))
  }

  override def fix(implicit doc: SemanticDocument): Patch = {
    val globalImports = collectGlobalImports(doc.tree)
    if (globalImports.isEmpty) Patch.empty else organizeImports(globalImports)
  }

  private def organizeImports(imports: Seq[Import])(implicit doc: SemanticDocument): Patch = {
    val (fullyQualifiedImporters, relativeImporters) =
      imports flatMap (_.importers) partition { importer =>
        topQualifierOf(importer.ref).symbol.owner == Symbol.RootPackage
      }

    // Organizes all the fully-qualified global importers.
    val (_, sortedImporterGroups: Seq[Seq[Importer]]) =
      fullyQualifiedImporters
        .groupBy(matchImportGroup) // Groups imports by importer prefix.
        .mapValues(organizeImporters) // Organize imports within the same group.
        .toSeq
        .sortBy { case (index, _) => index } // Sorts import groups by group index
        .unzip

    // Append all the relative imports (if any) at the end as a separate group with the original
    // order unchanged.
    val organizedImporterGroups: Seq[Seq[Importer]] =
      if (relativeImporters.isEmpty) sortedImporterGroups
      else sortedImporterGroups :+ relativeImporters

    // A patch that removes all the tokens forming the original imports.
    val removeOriginalImports = Patch.removeTokens(
      doc.tree.tokens.slice(
        imports.head.tokens.start,
        imports.last.tokens.end
      )
    )

    // A patch that inserts the organized imports. Note that global imports within curly-braced
    // packages must be indented accordingly, e.g.:
    //
    //   package foo {
    //     package bar {
    //       import baz
    //     }
    //   }
    val insertOrganizedImports = {
      def formatImporterGroup(group: Seq[Importer]): String =
        group
          .map(fixedImporterSyntax)
          .map("import " + _)
          .mkString("\n")

      val indent: Int = imports.head.tokens.head.pos.startColumn

      val indentedOutput: Seq[String] =
        organizedImporterGroups
          .map(formatImporterGroup)
          .mkString("\n\n")
          .split("\n")
          .zipWithIndex
          .map {
            case (line, 0)                 => line
            case (line, _) if line.isEmpty => line
            case (line, _)                 => " " * indent + line
          }

      Patch.addLeft(imports.head, indentedOutput mkString "\n")
    }

    (removeOriginalImports + insertOrganizedImports).atomic
  }

  private def sortImportees(importer: Importer): Importer = {
    import ImportSelectorsOrder._

    config.importSelectorsOrder match {
      case Ascii        => importer.copy(importees = importer.importees.sortBy(_.syntax))
      case SymbolsFirst => importer.copy(importees = sortImporteesSymbolsFirst(importer.importees))
      case Keep         => importer
    }
  }

  private def organizeImporters(importers: Seq[Importer]): Seq[Importer] = {
    import GroupedImports._

    val xs = config.groupedImports match {
      case Merge   => mergeImportersWithCommonPrefix(importers)
      case Explode => explodeGroupedImportees(importers)
      case Keep    => importers
    }

    xs map sortImportees sortBy (_.syntax)
  }

  // Returns the index of the group to which the given importer belongs.
  private def matchImportGroup(importer: Importer): Int = {
    val index = importMatchers indexWhere (_ matches importer)
    if (index > -1) index else wildcardGroupIndex
  }
}

object OrganizeImports {
  private def collectGlobalImports(tree: Tree): Seq[Import] = {
    def extractImports(stats: Seq[Stat]): Seq[Import] =
      stats takeWhile (_.is[Import]) collect { case i: Import => i }

    tree match {
      case Source(Seq(p: Pkg)) => collectGlobalImports(p)
      case Pkg(_, Seq(p: Pkg)) => collectGlobalImports(p)
      case Source(stats)       => extractImports(stats)
      case Pkg(_, stats)       => extractImports(stats)
      case _                   => Nil
    }
  }

  // Hack: The scalafix pretty-printer decides to add spaces after open and before close braces in
  // imports, i.e., "import a.{ b, c }" instead of "import a.{b, c}". Unfortunately, this behavior
  // cannot be overriden. This function removes the unwanted spaces as a workaround.
  private def fixedImporterSyntax(importer: Importer): String =
    importer.syntax
      .replace("{ ", "{")
      .replace(" }", "}")

  @tailrec private def topQualifierOf(term: Term): Term.Name =
    term match {
      case Term.Select(qualifier, _) => topQualifierOf(qualifier)
      case name: Term.Name           => name
    }

  private def sortImporteesSymbolsFirst(importees: List[Importee]): List[Importee] = {
    val symbols = ArrayBuffer.empty[Importee]
    val lowerCases = ArrayBuffer.empty[Importee]
    val upperCases = ArrayBuffer.empty[Importee]

    importees.foreach {
      case i if i.syntax.head.isLower => lowerCases += i
      case i if i.syntax.head.isUpper => upperCases += i
      case i                          => symbols += i
    }

    List(symbols, lowerCases, upperCases) flatMap (_ sortBy (_.syntax))
  }

  private def mergeImportersWithCommonPrefix(importers: Seq[Importer]): Seq[Importer] =
    importers.groupBy(_.ref.syntax).values.toSeq.map { group =>
      group.head.copy(importees = group.flatMap(_.importees).toList)
    }

  private def explodeGroupedImportees(importers: Seq[Importer]): Seq[Importer] =
    for {
      Importer(ref, importees) <- importers
      importee <- importees
    } yield Importer(ref, importee :: Nil)
}
