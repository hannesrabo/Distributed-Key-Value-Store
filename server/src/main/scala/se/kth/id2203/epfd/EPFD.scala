package se.kth.id2203.epfd

import se.kth.id2203.networking.{NetAddress, NetMessage}
import se.sics.kompics.network._
import se.sics.kompics.sl.{Init, _}
import se.sics.kompics.timer.{ScheduleTimeout, Timeout, Timer}
import se.sics.kompics.{KompicsEvent, Start, ComponentDefinition => _, Port => _}

//Define EPFD Implementation
class EPFD(init: Init[EPFD]) extends ComponentDefinition {

  //EPFD subscriptions
  val epfd = provides[EventuallyPerfectFailureDetector];
  val timer = requires[Timer];
  val net = requires[Network];

  // EPDF component state and initialization
  var (self, topology) = init match {
    case Init(addr: NetAddress, pi: Set[NetAddress]@unchecked) => (addr, pi)
  }

  val replicationConst = cfg.getValue[Int]("id2203.project.replicationConst")

  private val delta = 10
  private var period = 1000

  var alive = topology
  var suspected = Set[NetAddress]()
  var seqnum = 0

  def startTimer(delay: Long): Unit = {
    val scheduledTimeout = new ScheduleTimeout(period)
    scheduledTimeout.setTimeoutEvent(CheckTimeout(scheduledTimeout))
    trigger(scheduledTimeout -> timer)
  }

  //EPFD event handlers
  ctrl uponEvent {
    case _: Start => handle {
      startTimer(period);

    }
  }

  timer uponEvent {
    case CheckTimeout(_) => handle {

      if (!alive.intersect(suspected).isEmpty) {
        period += delta;
      }

      seqnum = seqnum + 1;

      for (p <- topology) {
        if (!alive.contains(p) && !suspected.contains(p)) {

          suspected = suspected + p;
          trigger(Suspect(p) -> epfd)

        } else if (alive.contains(p) && suspected.contains(p)) {
          suspected = suspected - p;
          trigger(Restore(p) -> epfd);
        }
        trigger(NetMessage(self, p, HeartbeatRequest(seqnum)) -> net);
      }

      if(alive.size >= replicationConst) {
        trigger(CorrectSystem() -> epfd)
      } else {
        trigger(FaultySystem() -> epfd)
      }

      alive = Set[NetAddress]();
      startTimer(period);
    }
  }

  net uponEvent {
    case NetMessage(header, HeartbeatRequest(seq)) => handle {
      trigger(NetMessage(self, header.src, HeartbeatReply(seqnum)) -> net);

    }
    case NetMessage(header, HeartbeatReply(seq)) => handle {
      if(seq == seqnum || suspected.contains(header.src)) {
        alive = alive + header.src;
      }

    }
  }
};