package se.kth.id2203.consensus

//import se.kth.edx.id2203.core.ExercisePrimitives.AddressUtils._
//import se.kth.edx.id2203.core.ExercisePrimitives.AddressUtils
//import se.kth.edx.id2203.core.Ports._
//import se.kth.edx.id2203.validation._
//import se.kth.edx.id2203.validation.checkBallotLE

import se.kth.id2203.networking.{NetAddress, NetHeader, NetMessage}
import se.sics.kompics.network._
import se.sics.kompics.sl._
import se.sics.kompics.timer.{ScheduleTimeout, Timeout, Timer}
import se.sics.kompics.{KompicsEvent, Start}

import scala.collection.mutable

case class CheckTimeout(timeout: ScheduleTimeout) extends Timeout(timeout)

case class HeartbeatReq(round: Long, highestBallot: Long) extends KompicsEvent

case class HeartbeatResp(round: Long, ballot: Long) extends KompicsEvent

class GossipLeaderElection {
  private val ballotOne = 0x0100000000l

  def ballotFromNAddress(n: Int, adr: Address): Long = {
    val nBytes = com.google.common.primitives.Ints.toByteArray(n)
    val addrBytes = com.google.common.primitives.Ints.toByteArray(adr.hashCode())
    val bytes = nBytes ++ addrBytes
    val r = com.google.common.primitives.Longs.fromByteArray(bytes)
    assert(r > 0); // should not produce negative numbers!

    return r
  }

  def incrementBallot(ballot: Long): Long = {
    ballot + ballotOne
  }

  class GossipLeaderElection(init: Init[GossipLeaderElection]) extends ComponentDefinition {

    private val ballotLeaderElection = provides[BallotLeaderElection]
    private val net = requires(Network)
    private val timer = requires[Timer]

    // TODO: Main sets "id2203.project.address" to self at start self
    private val self = init match {
      case Init(s: Address) => s
    }

    // TODO: Does this work? Maybe we need to set this somewhere.
    // The topology is almost certainly wrong
    private val topology = cfg.getValue[List[Address]]("ble.simulation.topology")
    private val delta = cfg.getValue[Long]("ble.simulation.delay")

    // TODO: Does the Address data structure apply here? Should probably be replace with
    // TODO: NetAddress instead.
    private val ballots = mutable.Map.empty[Address, Long]
    // TODO: Someone sets "id2203.project.keepAlivePeriod" (it is used anyway).
    private var period = cfg.getValue[Long]("ble.simulation.delay")
    private var round = 0l
    private var ballot = ballotFromNAddress(0, self)

    private var leader: Option[(Long, Address)] = None
    private var highestBallot: Long = ballot

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
      } else if (!leader.isDefined || top != leader.get) {
        highestBallot = topBallot
        leader = Some(top)
        trigger(BLE_Leader(topProcess, topBallot) -> ballotLeaderElection)
      }

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
          // TODO: We need to replace self and process with NetAddress
            trigger(NetMessage(self, process, HeartbeatReq(round, highestBallot)) -> net)
          //            trigger(PL_Send(process, HeartbeatReq(round, highestBallot)) -> pl)
        });

        startTimer(period);
      }
    }

    net uponEvent {
      case NetMessage(header, HeartbeatReq(r, hb)) => handle {
        if (hb > highestBallot)
          highestBallot = hb

        // TODO: pick out the src from the header.
        // Is the header.dst our address here????
        trigger(NetMessage(header.dst, header.src, HeartbeatResp(r, ballot)) -> net);
        //        trigger(PL_Send(src, HeartbeatResp(r, ballot)) -> pl)
      }
      case NetMessage(header, HeartbeatResp(r, b)) => handle {
        if (r == round)
        // TODO: Change the ballots array to contain net adresses instead!
          ballots += (header.src -> b)
        else
          period += delta
      }
    }
  }

}
