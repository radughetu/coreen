//
// $Id$

package coreen.server

import java.{lang => jl}
import java.io.File
import javax.servlet.http.HttpServletResponse

import scala.io.Source
import scala.collection.mutable.{ArrayBuffer, Map => MMap}

import org.squeryl.PrimitiveTypeMode._
import scalaj.collection.Imports._

import com.google.gwt.user.server.rpc.RemoteServiceServlet

import coreen.model.{Convert, Project => JProject, CompUnit => JCompUnit, Def => JDef, Use => JUse}
import coreen.model.{CompUnitDetail, DefContent, DefId, DefDetail, TypeDetail, TypeSummary}
import coreen.model.{Kind, Flavor, MemberInfo}
import coreen.persist.{DB, Decode, Project, CompUnit, Def}
import coreen.project.{Updater, Watcher}
import coreen.rpc.{ProjectService, ServiceException}

/** Provides the project servlet. */
trait ProjectServlet {
  this :Log with DB with Exec with Updater =>

  /**
   * The implementation of project services provided by {@link ProjectService}.
   */
  class ProjectServlet extends RemoteServiceServlet with ProjectService
  {
    // from interface ProjectService
    def getProject (id :Long) :JProject = Convert.toJava(requireProject(id))

    // from interface ProjectService
    def updateProject (proj :JProject) {
      transaction {
        update(_db.projects) { p =>
          where(p.id === proj.id).set(
            p.name := proj.name,
            p.rootPath := proj.rootPath,
            p.version := proj.version,
            p.srcDirs := strToOpt(proj.srcDirs),
            p.readerOpts := strToOpt(proj.readerOpts))
        }
      }
      // TODO: if the root path or source dirs changed, trigger a full rebuild
    }

    // from interface ProjectService
    def rebuildProject (id :Long) {
      val p = requireProject(id)
      _exec.executeJob("User triggered rebuild: " + p.name, () => _updater.update(p))
    }

    // from interface ProjectService
    def deleteProject (id :Long) :Unit = transaction {
      // delete the project and let foreign key constraints take care of the rest
      _db.projects.deleteWhere(p => p.id === id)
    }

    // from interface ProjectService
    def getCompUnits (projectId :Long) :Array[JCompUnit] = transaction {
      _db.compunits.where(cu => cu.projectId === projectId) map(Convert.toJava) toArray
    }

    // from interface ProjectService
    def getModsAndMembers (projectId :Long) :Array[Array[JDef]] = transaction {
      val mods = _db.loadModules(projectId) map(d => (d.id -> d)) toMap
      val members = _db.defs where(d => d.outerId in mods.keySet) toArray
      val modMems = members groupBy(_.outerId) map {
        case (id, dfs) => (mods(id) +: dfs.sorted(ByFlavorName)) map(Convert.toJava)
      }
      modMems.toArray sortBy(_.head.name)
    }

    /** Returns all modules in the specified project. */
    def getModules (projectId :Long) :Array[JDef] = transaction {
      _db.loadModules(projectId).toArray sorted(ByFlavorName) map(Convert.toJava)
    }

    // from interface ProjectService
    def getTypes (projectId :Long) :Array[JDef] = transaction {
      // load all types whose parent are the project's modules (all top-level types)
      val modIds = _db.loadModules(projectId).map(_.id)
      _db.defs.where(d => (d.outerId in modIds) and
                          (d.kind === Decode.kindToCode(Kind.TYPE))
                    ).toArray sortBy(_.name) map(Convert.toJava)
    }

    /** Returns all defs that are members of the supplied set of modules. */
    def getModsMembers (modIds :jl.Iterable[jl.Long]) :Array[JDef] = transaction {
      _db.defs.where(d => d.outerId in modIds.asScala.toSet).toArray map(Convert.toJava)
    }

    // from interface ProjectService
    def getMembers (defId :Long) :Array[JDef] = transaction {
      _db.defs.where(d => d.outerId === defId).toArray sortBy(_.name) map(Convert.toJava)
    }

    // from interface ProjectService
    def getMemberInfo (defId :Long) :MemberInfo = transaction {
      val info = initDefDetail(defId, new MemberInfo)
      val members = _db.defs.where(d => d.outerId === defId).toArray
      val memIds = members map(_.id) toSet
      val (sigs, docs) = (_db.loadSigs(memIds), _db.loadDocs(memIds))
      info.members = members sorted(ByFlavorName) map(
        d => Convert.toDefInfo(d, sigs.get(d.id), docs.get(d.id)))
      info
    }

    // from interface ProjectService
    def getCompUnit (unitId :Long) :CompUnitDetail = transaction {
      _db.compunits.lookup(unitId) map { unit =>
        loadCompUnitDetail(requireProject(unit.projectId), unit)
      } getOrElse(throw new ServiceException("e.no_such_unit"))
    }

    // from interface ProjectService
    def getDef (defId :Long) :DefDetail = transaction {
      initDefDetail(defId, new DefDetail)
    }

    // from interface ProjectService
    def getType (defId :Long) :TypeDetail = transaction {
      val td = initDefDetail(defId, new TypeDetail)
      td.members = _db.defs.where(d => d.outerId === defId).toArray sorted(ByFlavorName) map(
        Convert.toJava)
      td
    }

    // from interface ProjectService
    def getSummary (defId :Long) :TypeSummary = transaction {
      val ts = initDefDetail(defId, new TypeSummary)
      val mems = _db.defs.where(d => d.outerId === defId).toArray

      // load up all of the members defined in supertypes
      var supers = ArrayBuffer[Def]() // we'll accumulate our super defs into this buffer
      def loadSupers (superId :Long, filterIds :Set[Long]) :Array[Def] =
        _db.defs lookup(superId) match {
          case Some(sd) => {
            supers += sd
            val smems = _db.defs.where(
              d => d.outerId === superId and not (d.id in filterIds)) filter(
                d => JDef.isInherited(d.flags)) toArray;
            smems ++ loadSupers(sd.superId, filterIds ++ smems.map(_.superId))
          }
          case _ => Array[Def]()
        }
      // TODO: I don't think inherited but unimplemented interface members are handled here
      val superdefs = loadSupers(ts.superId, mems map(_.superId) toSet)

      val members = mems ++ superdefs
      val memIds = members map(_.id) toSet
      val (sigs, docs) = (_db.loadSigs(memIds), _db.loadDocs(memIds))
      ts.supers = (supers map(Convert.toJava) toArray)
      ts.members = members sorted(ByFlavorName) map(
        d => Convert.toDefInfo(d, sigs.get(d.id), docs.get(d.id)))
      ts
    }

    // from interface ProjectService
    def getContent (defId :Long) :DefContent = transaction {
      val d = requireDef(defId)
      val dc = initDefDetail(d, new DefContent)
      val p = requireProject(dc.unit.projectId)

      // load up the source text for this definition
      val text = loadSource(p, dc.unit.path)
      val start = text.lastIndexOf(LineSeparator, d.bodyStart)+1
      dc.text = text.substring(start, d.bodyEnd)

      // load up all defs and uses that are children of the def in question
      def loadDefs (parents :Set[Long]) :Seq[Def] = {
        val defs = _db.defs.where(d => d.outerId in parents).toSeq
        if (defs.isEmpty) defs
        else defs ++ loadDefs(defs.map(_.id) toSet)
      }
      dc.defs = (loadDefs(Set(defId)) :+ d).toArray sortBy(_.defStart) map(Convert.toDefId)
      dc.defs foreach { _.start -= start }
      val defIds = dc.defs map(_.id) toSet
      val uses = _db.uses.where(u => u.ownerId in defIds).toArray
      dc.uses = uses sortBy(_.useStart) map(Convert.toJava)
      dc.uses foreach { _.start -= start }
      dc
    }

    // from interface ProjectService
    def getSuperTypes (defId :Long) :Array[Array[JDef]] = transaction {
      val d = requireDef(defId)
      val buf = ArrayBuffer[Array[JDef]]()
      def addSuperTypes (d :Def) {
        val sdefs = _db.supers.left(d).toList
        buf += (sdefs.find(_.id == d.superId) match {
          case None => (d :: sdefs)
            case Some(pd) => {
              addSuperTypes(pd)
              (d :: sdefs.filterNot(_.id == pd.id))
            }
        }).map(Convert.toJava).toArray
      }
      addSuperTypes(d)
      buf.toArray
    }

    // from interface ProjectService
    def getSubTypes (defId :Long) :Array[Array[JDef]] = transaction {
      val d = requireDef(defId)
      val buf = ArrayBuffer[Array[JDef]]()
      def addSubTypes (defIds :Set[Long]) {
        val sdefs = from(_db.defs,_db.supers)(
          (d, s) => where((d.id === s.defId) and (s.superId in defIds)) select(d)) toArray;
        if (!sdefs.isEmpty) {
          buf += sdefs map(Convert.toJava)
          addSubTypes(sdefs map(_.id) toSet)
        }
      }
      buf += Array(Convert.toJava(d))
      addSubTypes(Set(d.id))
      buf.toArray
    }

    // from interface ProjectService
    def search (projectId :Long, query :String) :Array[DefDetail] = transaction {
      if (query.trim.length == 0) Array()
      else _db.resolveMatches(_db.findDefs(projectId, query, Kind.FUNC), () => new DefDetail)
    }

    // from interface ProjectService
    def findUses (defId :Long) :Array[ProjectService.UsesResult] = transaction {
      // load up ye metric tonne of metadata
      val uses = _db.uses.where(u => u.referentId === defId).take(MaxUsesResults).toSeq
      val byEncl = uses.groupBy(_.ownerId)
      val defs = _db.defs.where(d => d.id in byEncl.keySet).toSeq
      val enclUnitIds = defs.map(_.unitId).toSet
      val units = _db.compunits.where(cu => cu.id in enclUnitIds).map(cu => (cu.id, cu)).toMap
      val unitProjIds = units.values.map(_.projectId).toSet
      val projs = _db.projects.where(p => p.id in unitProjIds).map(p => (p.id, p)).toMap
      val unitSrc = MMap[Long, String]()

      // resolve the def detail information for all of our matches
      val results = _db.resolveMatches(defs, () => new ProjectService.UsesResult)

      // now fill in the enclosed uses and associated lines of source text
      for (res <- results) {
        res.uses = byEncl.getOrElse(res.id, Seq()).sortBy(_.useStart).map(Convert.toJava).toArray
        val src = unitSrc.getOrElseUpdate(res.unit.id, (
          for (u <- units.get(res.unit.id); p <- projs.get(u.projectId))
          yield loadSource(p, u.path)).headOption.getOrElse(
            "<missing project " + res.unit.projectId + ">"))
        val (lines, lineNos) = res.uses.map(extractUseLine(src)).unzip
        res.lines = lines.toArray
        res.lineNos = lineNos.toArray
      }

      results
    }

    override protected def doUnexpectedFailure (e :Throwable) {
      e.printStackTrace
      getThreadLocalResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage)
    }

    private def requireProject (id :Long) = transaction {
      _db.projects.lookup(id) match {
        case Some(p) => p
        case None => throw new ServiceException("e.no_such_project")
      }
    }

    private def requireDef (id :Long) = transaction {
      _db.defs.lookup(id) match {
        case Some(d) => d
        case None => {
          _log.warning("Requested unknown def " + id)
          throw new ServiceException("e.no_such_def")
        }
      }
    }

    private def initDefDetail[DD <: DefDetail] (df :Def, dd :DD) :DD = {
      Convert.initDefInfo(df, _db.sigs.where(s => s.defId === df.id).headOption,
                          _db.docs.where(d => d.defId === df.id).headOption, dd)
      dd.unit = _db.compunits.lookup(df.unitId) match {
        case Some(cu) => Convert.toJava(cu)
        case None => _log.warning("Missing compunit for def?",
                                  "defId", df.id, "unitId", df.unitId) ; new JCompUnit
      }
      dd.path = loadDefPath(df.outerId, Nil).toArray
      dd
    }

    private def loadDefPath (defId :Long, path :List[DefId]) :List[DefId] =
      if (defId == 0L) path else {
        val d = _db.defs.lookup(defId).get
        loadDefPath(d.outerId, Convert.toDefId(d) :: path)
      }

    private def initDefDetail[DD <: DefDetail] (defId :Long, dd :DD) :DD =
      initDefDetail(requireDef(defId), dd)

    private def loadCompUnitDetail (p :Project, unit :CompUnit) = {
      val detail = new CompUnitDetail
      detail.unit = Convert.toJava(unit)
      detail.text = loadSource(p, detail.unit.path)
      detail.defs = _db.defs.where(d => d.unitId === unit.id).toArray sortBy(_.defStart) map(
        Convert.toDefId)
      detail.uses = _db.uses.where(u => u.unitId === unit.id).toArray sortBy(_.useStart) map(
        Convert.toJava)
      detail
    }

    private def extractUseLine (src :String)(use :JUse) = {
      if (use.start >= src.length) {
        _log.warning("Invalid use? " + use + " (src length " + src.length + ")")
        ("<invalid>", -1)
      } else {
        var pos = -1
        var lpos = 0
        var lineNo = 1
        while (pos < use.start) {
          lpos = pos+1
          lineNo += 1
          pos = src.indexOf("\n", lpos)
          if (pos == -1) pos = src.length
        }
        use.start -= (lpos)
        (src.substring(lpos, pos), lineNo)
      }
    }

    private def strToOpt (value :String) =
      if (value == null || value == "") None else Some(value)

    private def loadSource (p :Project, path :String) =
      Source.fromURI(new File(p.rootPath).toURI.resolve(path)).mkString("")

    // defines the sort ordering of def flavors
    private val FlavorPriority = List(Flavor.TYPE_PARAM,
                                      Flavor.ANNOTATION,
                                      Flavor.ENUM,
                                      Flavor.INTERFACE,
                                      Flavor.ABSTRACT_CLASS,
                                      Flavor.CLASS,
                                      Flavor.OBJECT,
                                      Flavor.ABSTRACT_OBJECT,
                                      Flavor.STATIC_FIELD,
                                      Flavor.STATIC_METHOD,
                                      Flavor.FIELD,
                                      Flavor.CONSTRUCTOR,
                                      Flavor.ABSTRACT_METHOD,
                                      Flavor.METHOD
                                    ).map(Decode.flavorToCode).zipWithIndex.toMap

    private val ByFlavorName = new Ordering[Def] {
      def compare (a :Def, b :Def) = {
        val rv = FlavorPriority.getOrElse(a.flavor, 99) - FlavorPriority.getOrElse(b.flavor, 99)
        if (rv == 0) a.name.compareTo(b.name) else rv
      }
    }

    private val LineSeparator = System.getProperty("line.separator")
    private val MaxUsesResults = 256 // we loves the binary
  }
}
