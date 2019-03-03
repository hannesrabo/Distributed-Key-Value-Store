package se.kth.id2203.epfd

import se.kth.id2203.networking.NetAddress
import se.sics.kompics.KompicsEvent
import se.sics.kompics.sl.Port

class EventuallyPerfectFailureDetector extends Port {
  indication[Suspect];
  indication[Restore];
}

case class Suspect(process: NetAddress) extends KompicsEvent
case class Restore(process: NetAddress) extends KompicsEvent
case class FaultySystem() extends KompicsEvent
case class CorrectSystem() extends KompicsEvent