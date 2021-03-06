//
// $Id$

package coreen.server

/** Provides console services to which server entities can log and client entities can see. */
trait Console {
  trait Writer {
    /** Appends the supplied line to this console. */
    def append (line :String) {
      append(List(line))
    }

    /** Appends the supplied message and exception stack trace to this console. */
    def append (line :String, error :Throwable) {
      append(line :: error.toString :: (error.getStackTrace map(_.toString) toList))
    }

    /** Appends the supplied lines to this console. */
    def append (lines :Seq[String]) :Unit

    /** Completes this writing session. */
    def close () :Unit
  }

  trait Service {
    /** Returns true if an active session is writing to the specified id. */
    def isOpen (id :String) :Boolean

    /** Starts a writing session to the console with the specified id.
     * If a writing session is currently active, an {@link IllegalStateException} is thrown. */
    def start (id :String) :Writer

    /** Fetches lines from the console with the specified id. Returns an empty sequence if the
     * buffer does not exist.
     * @param fromLine the number of lines to skip (which the caller presumably already has). */
    def fetch (id :String, fromLine :Int) :Seq[String]
  }

  /** Provides console services. */
  val _console :Service
}

/** A concrete implementation of {@link Console}. */
trait ConsoleComponent extends Component with Console {
  val _console = new Service {
    def isOpen (id :String) = synchronized {
      _buffers.get(id) match {
        case Some(b) => b.isOpen
        case None => false
      }
    }

    def start (id :String) :Writer = synchronized {
      // make sure we don't already have an open buffer
      _buffers.get(id) map { b =>
        if (b.isOpen)
          throw new IllegalArgumentException("Open buffer already exists for id " + id)
      }

      val b = new Buffer(id)
      _buffers += (id -> b)
      b
    }

    def fetch (id :String, fromLine :Int) :Seq[String] = synchronized { _buffers.get(id) } match {
      case Some(b) => b.lines.drop(fromLine)
      case None => Array[String]()
    }
  }

  /** Contains the data for a single console buffer. */
  private class Buffer (id :String) extends Writer {
    /** Whether this buffer is currently being written. */
    var isOpen = true

    /** Thus buffer's data. */
    var lines = Array[String]()

    // from trait Writer
    def append (lines :Seq[String]) {
      this.lines ++= lines
    }

    // from trait Writer
    def close () {
      isOpen = false
    }
  }

  private val _buffers = collection.mutable.Map[String,Buffer]()
}

trait StdoutConsoleComponent  extends Component with Console {
  val _console = new Service {
    def isOpen (id :String) = false

    def start (id :String) :Writer = new Writer {
      def append (lines :Seq[String]) {
        lines.foreach(println)
      }
      def close () {
        // noop!
      }
    }

    def fetch (id :String, fromLine :Int) :Seq[String] = Array[String]()
  }
}
