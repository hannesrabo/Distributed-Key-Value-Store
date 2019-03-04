package se.kth.id2203.consensus

import se.kth.id2203.networking.{NetAddress, NetHeader, NetMessage}
import se.sics.kompics.network._
import se.sics.kompics.sl._
import se.sics.kompics.timer.{ScheduleTimeout, Timeout, Timer}
import se.sics.kompics.{KompicsEvent, Start}

import scala.collection.mutable

case class CheckTimeout(timeout: ScheduleTimeout) extends Timeout(timeout)

case class HeartbeatReq(round: Long, highestBallot: Long) extends KompicsEvent

case class HeartbeatResp(round: Long, ballot: Long) extends KompicsEvent

class GossipLeaderElection(init: Init[GossipLeaderElection]) extends ComponentDefinition {
  val ballotLeaderElection = provides[BallotLeaderElection]
  val net = requires[Network]
  val timer = requires[Timer]

  val (self, topology) = init match {
    case Init(self: NetAddress, topology: Set[NetAddress]@unchecked) => (self, topology)
  }

  private val delta = 10
  private var period = 1000
  private val ballotOne = 0x0100000000l
  private val ballots = mutable.Map.empty[NetAddress, Long]
  private var round = 0l
  private var ballot = ballotFromNAddress(0, self)
  private var leader: Option[(Long, NetAddress)] = None
  private var highestBallot: Long = ballot

  def ballotFromNAddress(n: Int, adr: NetAddress): Long = {
    val nBytes = com.google.common.primitives.Ints.toByteArray(n)
    val addrBytes = com.google.common.primitives.Ints.toByteArray(adr.hashCode())
    val bytes = nBytes ++ addrBytes
    val r = com.google.common.primitives.Longs.fromByteArray(bytes)
    assert(r > 0); // should not produce negative numbers!

    r
  }

  private def startTimer(delay: Long): Unit = {
    val scheduledTimeout = new ScheduleTimeout(period)
    scheduledTimeout.setTimeoutEvent(CheckTimeout(scheduledTimeout))
    trigger(scheduledTimeout -> timer)
  }

  private def checkLeader() {
    val (topProcess, topBallot) = (ballots + (self -> ballot)).maxBy(_._2)
    val top = (topBallot, topProcess)

    if (topBallot < highestBallot) {
      while (ballot <= highestBallot) {
        ballot = incrementBallot(ballot)
      }

      leader = None
    } else if (leader.isEmpty || top != leader.get) {
      highestBallot = topBallot
      leader = Some(top)
      trigger(BLE_Leader(topProcess, topBallot) -> ballotLeaderElection)
    }

  }

  def incrementBallot(ballot: Long): Long = {
    ballot + ballotOne
  }

  ctrl uponEvent {
    case _: Start => handle {
      startTimer(period)
    }
  }

  timer uponEvent {
    case CheckTimeout(_) => handle {
      if (ballots.size + 1 >= Math.ceil(topology.size / 2).toInt) {
        checkLeader()
      }
      ballots.clear
      round += 1
      topology.foreach(process => {
        if (process != self)
          trigger(NetMessage(self, process, HeartbeatReq(round, highestBallot)) -> net)
      })

      startTimer(period)
    }
  }

  net uponEvent {
    case NetMessage(header, HeartbeatReq(r, hb)) => handle {
      if (hb > highestBallot)
        highestBallot = hb

      trigger(NetMessage(self, header.src, HeartbeatResp(r, ballot)) -> net)
    }
    case NetMessage(header, HeartbeatResp(r, b)) => handle {
      if (r == round)
        ballots += (header.src -> b)
      else
        period += delta
    }
  }

}
