package se.kth.id2203.consensus


import se.kth.id2203.networking.{NetAddress, NetMessage}
import se.sics.kompics.sl._
import se.sics.kompics.network._
import se.sics.kompics.KompicsEvent

import scala.collection.mutable

// Internal events
case class Prepare(nL: Long, ld: Int, na: Long) extends KompicsEvent

case class Promise(nL: Long, na: Long, suffix: List[RSM_Command], ld: Int) extends KompicsEvent

case class AcceptSync(nL: Long, suffix: List[RSM_Command], ld: Int) extends KompicsEvent

case class Accept(nL: Long, c: RSM_Command) extends KompicsEvent

case class Accepted(nL: Long, m: Int) extends KompicsEvent

case class Decide(ld: Int, nL: Long) extends KompicsEvent

object State extends Enumeration {
  type State = Value
  val PREPARE, ACCEPT, UNKNOWN = Value
}

object Role extends Enumeration {
  type Role = Value
  val LEADER, FOLLOWER = Value
}

class SequencePaxos(init: Init[SequencePaxos]) extends ComponentDefinition {

  // Ports
  val sequenceConsensus = provides[SequenceConsensus]
  val ballotLeaderElection = requires[BallotLeaderElection]
  val net = requires[Network]

  // Used internally
  import Role._
  import State._

  val (self, pi, others) = init match {
    case Init(addr: NetAddress, pi: Set[NetAddress]@unchecked) => (addr, pi, pi - addr)
  }

  val las = mutable.Map.empty[NetAddress, Int]
  val lds = mutable.Map.empty[NetAddress, Int]
  val acks = mutable.Map.empty[NetAddress, (Long, List[RSM_Command])]
  var state = (FOLLOWER, UNKNOWN)
  var nL = 0l
  var nProm = 0l
  var leader: Option[NetAddress] = None
  var na = 0l
  var va = List.empty[RSM_Command]
  var ld = 0

  // leader stateDecide(lc, nL)
  var propCmds = List.empty[RSM_Command]
  var lc = 0

  def suffix(s: List[RSM_Command], l: Int): List[RSM_Command] = {
    s.drop(l)
  }

  def prefix(s: List[RSM_Command], l: Int): List[RSM_Command] = {
    s.take(l)
  }

  ballotLeaderElection uponEvent {
    case BLE_Leader(l, n) => handle {
//      printf(s"PROPOSING NEW LEADER: $l [$self] (n: $n, nL: $nL)\n")
//      printf(s"TOPOLOGY: $pi\n")
      if (n > nL) {
        nL = n
        leader = Some(l)
//        printf(s"NEW LEADER: $l [$self] (nL: $nL, nProm: $nProm)\n")
        if (self == l && nL > nProm) {
//          printf(s"IM THE LEADER [$self]\n")
          // Im a new leader, reset everything
          state = (LEADER, PREPARE)
          propCmds = List.empty
          las.clear
          lds.clear
          acks.clear
          lc = 0
          // TODO: Should we support groups with only one member?
          // In that case we should set our state to Leader, Accept
          others.foreach(p =>
            trigger(NetMessage(self, p, Prepare(nL, ld, na)) -> net)
          )

          // Create the initial state
          acks += (l -> (na, va.takeRight(va.size - ld)))
          lds += (self -> ld)
          nProm = nL

          // TODO: This algorithm does not have support for groups with only one
          // TODO: regardless of this...
//          if (others.size < 1)
//            checkMajority // This makes sure we get immediate leadership if we are alone

        } else {
          state = (FOLLOWER, state._2)
        }
      }
    }
  }

  def checkMajority = {
    if (acks.size >= Math.ceil((pi.size + 1) / 2).toInt) {
//      printf(s"WAS ELECTED LEADER BY MAJORITY $self\n")
      // We can drop the key and then drop the round
      val sfx = acks.values.maxBy(_._1)._2

      // Create the new accepted history
      va = va.take(ld) ++ sfx ++ propCmds

      las += (self -> va.size) // length of accepted elements
      propCmds = List.empty
      state = (LEADER, ACCEPT)
      pi.filter(p => p != self && lds.contains(p))
        .foreach(p => {
          // grab all elements not yet accepted
          val sfxp = va.takeRight(va.size - lds(p))
          trigger(NetMessage(self, p, AcceptSync(nL, sfxp, lds(p))) -> net)
        })
    }
  }

  net uponEvent {
    case NetMessage(header, Prepare(np, ldp, n)) => handle {
      // Send elements that the leader is missing.
      if (nProm < np) {
        nProm = np
        state = (FOLLOWER, PREPARE)
        val sfx
        = if (na > n)
          va.takeRight(va.size - ldp)
        else
          List.empty

        trigger(NetMessage(self, header.src, Promise(np, na, sfx, ld)) -> net)
      }
    }

    case NetMessage(header, Promise(n, na, sfxa, lda)) => handle {
      if ((n == nL) && (state == (LEADER, PREPARE))) {

        acks += (header.src -> (na, sfxa))
        lds += (header.src -> lda)
        // If we have a majority
        checkMajority

      } else if ((n == nL) && (state == (LEADER, ACCEPT))) {
        // Late answers
        lds += (header.src -> lda)
        val sfx = va.takeRight(va.size - lds(header.src))
        trigger(NetMessage(self, header.src, AcceptSync(nL, sfx, lds(header.src))) -> net)

        if (lc != 0) // Update with newly decided values
          trigger(NetMessage(self, header.src, Decide(ld, nL)) -> net)
      }
    }
    case NetMessage(header, AcceptSync(nL, sfx, ldp)) => handle {
      if ((nProm == nL) && (state == (FOLLOWER, PREPARE))) {
        na = nL
        va = va.take(ldp) ++ sfx
        trigger(NetMessage(self, header.src, Accepted(nL, va.size)) -> net)
        state = (FOLLOWER, ACCEPT)
      }
    }
    case NetMessage(header, Accept(nL, c)) => handle {
      if ((nProm == nL) && (state == (FOLLOWER, ACCEPT))) {
        va ++= List(c)
        trigger(NetMessage(self, header.src, Accepted(nL, va.size)) -> net)
      }
    }
    case NetMessage(_, Decide(l, nL)) => handle {
      if (nProm == nL) {
        while (ld < l) {
          trigger(SC_Decide(va(ld)) -> sequenceConsensus)
          ld = ld + 1
        }
      }
    }
    case NetMessage(header, Accepted(n, m)) => handle {
      if ((n == nL) && (state == (LEADER, ACCEPT))) {
        las += (header.src -> m)
        if (
          lc < m &&
            pi.count(p => las.contains(p) && las(p) >= m)
              >=
              Math.ceil((pi.size + 1) / 2).toInt
        ) {
          lc = m
          pi.filter(lds contains)
            .foreach(p =>
              trigger(NetMessage(self, p, Decide(lc, nL)) -> net)
            )
        }
      }
    }
  }

  sequenceConsensus uponEvent {
    case SC_Propose(c) => handle {
      if (state == (LEADER, PREPARE)) {
//        printf(s"RECEIVING COMMAND: $self (has leader $leader)\n");
        propCmds ++= List(c)
      }
      else if (state == (LEADER, ACCEPT)) {
//        printf(s"INSTANT ACCEPT: $self (has leader $leader)\n");
        va ++= List(c)
        las(self) = las(self) + 1
        pi.filter(p => p != self && lds.contains(p))
          .foreach(p =>
            trigger(NetMessage(self, p, Accept(nL, c)) -> net)
          )
      }
    }
  }

}
