//
// $Id$

package coreen.project

import java.io.{File, StringReader}
import java.net.URI
import java.util.concurrent.Callable

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.xml.{XML, Elem}

import org.squeryl.PrimitiveTypeMode._

import coreen.nml.SourceModel
import coreen.nml.SourceModel._
import coreen.model.{Def => JDef}
import coreen.persist.{DB, Project, CompUnit, Def, DefName, Use}
import coreen.server.{Log, Exec, Dirs}

/** Provides project updating services. */
trait Updater {
  this :Log with Exec with DB with Dirs =>

  /** Handles updating projects. */
  object _updater {
    /**
     * (Re)imports the contents of the specified project. This includes:
     * <ul>
     *  <li>scanning the root path of the supplied project for compilation units</li>
     *  <li>grouping them by language</li>
     *  <li>running the appropriate readers to convert them to name-resolved form</li>
     *  <li>clearing the current project contents from the database</li>
     *  <li>loading the name-resolved metadata into the database</li>
     * </ul>
     * This is very disk and compute intensive and should be done on a background thread.
     *
     * @param log a callback function which will be passed log messages to communicate status.
     */
    def update (p :Project, ulog :String=>Unit = noop => ()) {
      ulog("Finding compilation units...")

      // first figure out what sort of source files we see in the project
      val types = collectFileTypes(new File(p.rootPath))

      // fire up readers to handle all types of files we find in the project
      val readers = Map() ++ (types flatMap(t => readerForType(t) map(r => (t -> r))))
      ulog("Processing compilation units of type " + readers.keySet.mkString(", ") + "...")
      readers.values map(_.invoke(p, ulog))
    }

    abstract class Reader {
      def invoke (p :Project, ulog :String=>Unit) {
        _log.info("Invoking reader: " + (args :+ p.rootPath).mkString(" "))
        val proc = Runtime.getRuntime.exec((args :+ p.rootPath).toArray)

        // read stderr on a separate thread so that we can ensure that stdout and stderr are both
        // actively drained, preventing the process from blocking
        val errLines = _exec.submit(new Callable[Array[String]] {
          def call = Source.fromInputStream(proc.getErrorStream).getLines.toArray
        })

        // consume stdout from the reader, accumulating <compunit ...>...</compunit> into a buffer
        // and processing each complete unit that we receive; anything in between compunit elements
        // is reported verbatim to the status log
        val cus = time("parseCompUnits") {
          parseCompUnits(p, ulog, Source.fromInputStream(proc.getInputStream).getLines)
        }
        println("Parsed " + cus.size + " compunits.")

        // now that we've totally drained stdout, we can wait for stderr output and log it
        val errs = errLines.get

        // report any error status code (TODO: we probably don't really need to do this)
        val ecode = proc.waitFor
        if (ecode != 0) {
          ulog("Reader exited with status: " + ecode)
          errs.foreach(ulog)
          return // leave the project as is; TODO: maybe not if this is the first import...
        }

        // determine which CUs we knew about before
        val oldCUs = time("loadOldUnits") {
          transaction { _db.compunits where(cu => cu.projectId === p.id) toList }
        }

        // update compunit data, and construct a mapping from compunit path to id
        val newPaths = Set("") ++ (cus map(_.src))
        val toDelete = oldCUs filterNot(cu => newPaths(cu.path)) map(_.id) toSet
        val toAdd = newPaths -- (oldCUs map(_.path))
        val toUpdate = oldCUs filterNot(cu => toDelete(cu.id)) map(_.id) toSet
        val cuIds = collection.mutable.Map[String,Long]()
        val now = System.currentTimeMillis
        transaction {
          if (!toDelete.isEmpty) {
            _db.compunits.deleteWhere(cu => cu.id in toDelete)
            ulog("Removed " + toDelete.size + " obsolete compunits.")
          }
          if (!toAdd.isEmpty) {
            toAdd.map(CompUnit(p.id, _, now)) foreach { cu =>
              _db.compunits.insert(cu)
              // add the id of the newly inserted unit to our (path -> id) mapping
              cuIds += (cu.path -> cu.id)
            }
            ulog("Added " + toAdd.size + " new compunits.")
          }
          if (!toUpdate.isEmpty) {
            _db.compunits.update(cu =>
              where(cu.id in toUpdate) set(cu.lastUpdated := now))
            // add the ids of the updated units to our (path -> id) mapping
            oldCUs filter(cu => toUpdate(cu.id)) foreach { cu => cuIds += (cu.path -> cu.id) }
            ulog("Updated " + toUpdate.size + " compunits.")
          }
        }

        // (if necessary) create a fake comp unit which will act as a container for module
        // declarations (TODO: support package-info.java files, Scala package objects, and
        // languages that have a compunit which can be reasonably associated with a module
        // definition)
        val cuDef = transaction {
          _db.compunits.where(cu => cu.projectId === p.id and cu.path === "").headOption.
            getOrElse(_db.compunits.insert(CompUnit(p.id, "", now)))
        }

        // we extract all of the module definitions, map them by id, and then select (arbitrarily)
        // the first occurance of a definition for the specified module id to represent that
        // module; we then strip those defs of their subdefs (which will be processed later) and
        // then process the whole list as if they were all part of one "declare all the modules in
        // this project" compilation unit
        val byId = cus.flatMap(_.allDefs) filter(_.typ == JDef.Type.MODULE) groupBy(_.id)
        val modDefs = byId.values map(_.head) map(_.copy(defs = Nil))
        val modIds = processCompUnit(cuDef.id, Map(), CompUnitElem("", modDefs toSeq))

        // process each compunit individually
        for (cu <- cus) {
          ulog("Processing " + cu.src + "...")
          processCompUnit(cuIds(cu.src), modIds, cu)
        }

        ulog("Processing complete!")
        _timings.toList sortBy(_._2) foreach(println)
      }

      def parseCompUnits (p :Project, ulog :String=>Unit, lines :Iterator[String]) = {
        // obtain a sane prefix we can use to relativize the comp unit source URIs
        val uriRoot = new File(p.rootPath).getCanonicalFile.toURI.getPath
        assert(uriRoot.endsWith("/"))

        var accmode = false
        var accum = new StringBuilder
        val cubuf = ArrayBuffer[CompUnitElem]()
        for (line <- lines) {
          accmode = accmode || line.trim.startsWith("<compunit");
          if (!accmode) ulog(line)
          else {
            accum.append(line)
            accmode = !line.trim.startsWith("</compunit>")
            if (!accmode) {
              try {
                val cu = SourceModel.parse(XML.load(new StringReader(accum.toString)))
                val curi = new URI(cu.src)
                if (curi.getPath.startsWith(uriRoot))
                  cu.src = curi.getPath.substring(uriRoot.length)
                cubuf += cu
              } catch {
                case e => ulog("Error parsing reader output [" + e + "]: " +
                               truncate(accum.toString, 100))
              }
              accum.setLength(0)
            }
          }
        }
        cubuf.toList
      }

      def args :List[String]
    }

    def processCompUnit (unitId :Long, modIds :Map[String,Long], cu :CompUnitElem) = {
      println("Processing " + unitId + " " + cu.src)

      // load up existing defs for this compunit, and a mapping from fqName to defId
      val (edefs, emap) = time("loadNames") {
        transaction {
          val tmp = _db.defs.where(d => d.unitId === unitId) map(d => (d.id, d)) toMap; // grumble
          (tmp, _db.loadDefNames(tmp.keySet))
        }
      }
      println("Loaded " + edefs.size + " defs and " + emap.size + " names")

      // figure out which defs to add, which to update, and which to delete
      val (newDefs, oldDefs) = (cu.allIds, emap.keySet)
      val toDelete = oldDefs -- newDefs
      val (toAdd, toUpdate) = (newDefs -- oldDefs -- modIds.keySet, oldDefs -- toDelete)

      transaction {
        // add the new defs to the defname map to assign them ids
        time("insertNewNames") {
          _db.defmap.insert(toAdd.map(DefName(_)))
        }
        // we have to load the newly assigned ids back out of the db as there's no way to get the
        // newly assigned ids when using a batch update
        val nmap = time("loadDefIds") { _db.loadDefIds(toAdd) }
        val ids = modIds ++ emap ++ nmap

        // now convert the defelems into defs using the fqName to id map
        def processDefs (parentId :Long)(out :Map[Long,Def], df :DefElem) :Map[Long,Def] = {
          val ndef = Def(ids(df.id), parentId, unitId, df.name, _db.typeToCode(df.typ),
                         stropt(df.sig), stropt(df.doc),
                         df.start, df.start+df.name.length, df.bodyStart, df.bodyEnd)
          ((out + (ndef.id -> ndef)) /: df.defs)(processDefs(ndef.id))
        }
        val ndefs = (Map[Long,Def]() /: cu.defs)(processDefs(0L))

        // insert, update, and delete
        if (!toAdd.isEmpty) {
          val added = toAdd map(nmap) map(ndefs)
          time("addNewDefs") { _db.defs.insert(added) }
          println("Inserted " + toAdd.size + " new defs")
        }
        if (!toUpdate.isEmpty) {
          _db.defs.update(toUpdate map(emap) map(ndefs))
          println("Updated " + toUpdate.size + " defs")
        }
        if (!toDelete.isEmpty) {
          val toDelIds = toDelete map(emap)
          time("deleteOldDefs") { _db.defs.deleteWhere(d => d.id in toDelIds) }
          println("Deleted " + toDelete.size + " defs")
        }

        // delete the old uses recorded for this compunit
        time("deleteOldUses") { _db.uses.deleteWhere(u => u.unitId === unitId) }

        // convert the useelems into (use, referentFqName) pairs
        def processUses (out :Vector[(Use,String)], df :DefElem) :Vector[(Use,String)] = {
          val defId = ids(df.id)
          val nuses = df.uses.map(
            u => (Use(unitId, defId, -1, u.start, u.start + u.name.length), u.target))
          ((out ++ nuses) /: df.defs)(processUses)
        }
        val nuses = (Vector[(Use,String)]() /: cu.defs)(processUses)

        // now look up the referents
        val refFqNames = Set() ++ nuses.map(_._2)
        val refIds = time("loadRefIds") { _db.loadDefIds(refFqNames) }
        // TODO: generate placeholder defs for unknown referents
        val missingIds = refFqNames -- refIds.keySet
        val (bound, unbound) = ((List[Use](),List[(Use,String)]()) /: nuses)((
          acc, up) => refIds.get(up._2) match {
          case Some(id) => ((up._1 copy (referentId = id)) :: acc._1, acc._2)
          case None => (acc._1, up :: acc._2)
        })
        time("insertUses") { _db.uses.insert(bound) }

        ids // return the mapping from fqName to id for all defs in this compunit
      }
    }

    class JavaReader (
      classname :String, classpath :List[File], javaArgs :List[String]
    ) extends Reader {
      val javabin = mkFile(new File(System.getProperty("java.home")), "bin", "java")
      def args = (javabin.getCanonicalPath :: "-classpath" ::
                  classpath.map(_.getAbsolutePath).mkString(File.pathSeparator) ::
                  classname :: javaArgs)
    }

    // TEMP: profiling helper
    def time[T] (id :String)(action : => T) = {
      val start = System.nanoTime
      val r = action
      val elapsed = System.nanoTime - start
      _timings(id) = elapsed + _timings.getOrElse(id, 0L)
      r
    }
    val _timings = collection.mutable.Map[String,Long]()
    // END TEMP

    def stropt (text :String) = text match {
      case null | "" => None
      case str => Some(str)
    }

    def mkFile (root :File, path :String*) = (root /: path)(new File(_, _))

    def getToolsJar = {
      val jhome = new File(System.getProperty("java.home"))
      val tools = mkFile(jhome.getParentFile, "lib", "tools.jar")
      val classes = mkFile(jhome.getParentFile, "Classes", "classes.jar")
      if (tools.exists) tools
      else if (classes.exists) classes
      else error("Can't find tools.jar or classes.jar")
    }

    def createJavaJavaReader = _appdir match {
      case Some(appdir) => new JavaReader(
        "coreen.java.Main",
        List(getToolsJar, mkFile(appdir, "code", "coreen-java-reader.jar")),
        List())
      case None => new JavaReader(
        "coreen.java.Main",
        List(getToolsJar,
             mkFile(new File("java-reader"), "target", "scala_2.8.0",
                    "coreen-java-reader_2.8.0-0.1.min.jar")),
        List())
    }

    def readerForType (typ :String) :Option[Reader] = typ match {
      case "java" => Some(createJavaJavaReader)
      case _ => None
    }

    def collectFileTypes (file :File) :Set[String] = {
      def suffix (name :String) = name.substring(name.lastIndexOf(".")+1)
      if (file.isDirectory) file.listFiles.toSet flatMap(collectFileTypes)
      else Set(suffix(file.getName))
    }

    def truncate (text :String, length :Int) =
      if (text.length <= length) text
      else text.substring(0, length) + "..."
  }
}
