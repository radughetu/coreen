//
// $Id$

package coreen.server

import com.samskivert.util.Logger

/** Provides logging services. */
trait Log {
  /** For great logging. */
  val _log :Logger
}

/** A concrete implementation of {@link Log}. */
trait LogComponent extends Component with Log {
  /** For great logging. */
  val _log = com.samskivert.util.Logger.getLogger("coreen")
}