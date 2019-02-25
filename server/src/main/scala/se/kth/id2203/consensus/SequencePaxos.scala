package se.kth.id2203.consensus


import se.kth.id2203.networking.NetMessage
import se.sics.kompics.sl._
import se.sics.kompics.network._
import se.sics.kompics.KompicsEvent

import scala.collection.mutable;

//import se.kth.edx.id2203.core.ExercisePrimitives.AddressUtils._
//import se.kth.edx.id2203.core.ExercisePrimitives.AddressUtils
//import se.kth.edx.id2203.validation._
//import se.kth.edx.id2203.core.Ports.{SequenceConsensus, _}


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

  import Role._
  import State._

  // TODO: replace Address
  val (self, pi, others) = init match {
    case Init(addr: Address, pi: Set[Address]@unchecked) => (addr, pi, pi - addr)
  }

  // TODO: Replace Address with NetAddress??
  val las = mutable.Map.empty[Address, Int]
  val lds = mutable.Map.empty[Address, Int]
  val acks = mutable.Map.empty[Address, (Long, List[RSM_Command])]
  var state = (FOLLOWER, UNKNOWN)
  var nL = 0l
  var nProm = 0l
  var leader: Option[Address] = None
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
      if (n > nL) {
        nL = n;
        leader = Some(l);
        if (self == l && nL > nProm) {
          // Im a new leader, reset everything
          state = (LEADER, PREPARE);
          propCmds = List.empty;
          las.clear;
          lds.clear;
          acks.clear;
          lc = 0;
          others.foreach(p =>
            trigger(NetMessage(self, p, Prepare(nL, ld, na)) -> net)
            //            trigger(PL_Send(p, Prepare(nL, ld, na)) -> pl)
          );

          // Create the initial state
          acks += (l -> (na, va.takeRight(va.size - ld)));
          lds += (self -> ld);
          nProm = nL;
        } else {
          state = (FOLLOWER, state._2);
        }
      }
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

        // TODO: Replace header.dst??
        trigger(NetMessage(header.dst, header.src, Promise(np, na, sfx, ld)) -> net)
        //        trigger(PL_Send(p, Promise(np, na, sfx, ld)) -> pl);

      }
    }
    case NetMessage(header, Promise(n, na, sfxa, lda)) => handle {
      if ((n == nL) && (state == (LEADER, PREPARE))) {

        // TODO: header/lds/acks data structure
        acks += (header.src -> (na, sfxa))
        lds += (header -> lda)
        // If we have a majority
        if (acks.size == Math.ceil((pi.size + 1) / 2).toInt) {
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
              trigger(NetMessage(header.dst, p, AcceptSync(nL, sfxp, lds(p))) -> net)
              //              trigger(PL_Send(p, AcceptSync(nL, sfxp, lds(p))) -> pl)
            })
        }

      } else if ((n == nL) && (state == (LEADER, ACCEPT))) {
        // Late answers
        lds += (header.src -> lda);
        val sfx = va.takeRight(va.size - lds(header.src));
        trigger(NetMessage(header.dst, header.src, AcceptSync(nL, sfx, lds(header.src))) -> net)
        //        trigger(PL_Send(a, AcceptSync(nL, sfx, lds(a))) -> pl);

        if (lc != 0) // Update with newly decided values
          trigger(NetMessage(header.dst, header.src, Decide(ld, nL)) -> net)
        //          trigger(PL_Send(a, Decide(ld, nL)) -> pl);
      }
    }
    case NetMessage(header, AcceptSync(nL, sfx, ldp)) => handle {
      if ((nProm == nL) && (state == (FOLLOWER, PREPARE))) {
        na = nL
        va = va.take(ldp) ++ sfx
        //        trigger(PL_Send(p, Accepted(nL, va.size)) -> pl);
        trigger(NetMessage(header.dst, header.src, Accepted(nL, va.size)) -> net)
        state = (FOLLOWER, ACCEPT)
      }
    }
    case NetMessage(header, Accept(nL, c)) => handle {
      if ((nProm == nL) && (state == (FOLLOWER, ACCEPT))) {
        va ++= List(c)
        //        trigger(PL_Send(p, Accepted(nL, va.size)) -> pl);
        trigger(NetMessage(header.dst, header.src, Accepted(nL, va.size)) -> net)
      }
    }
    case NetMessage(_, Decide(l, nL)) => handle {
      if (nProm == nL) {
        while (ld < l) {
          trigger(SC_Decide(va(ld)) -> sequenceConsensus);
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
              trigger(NetMessage(header.dst, p, Decide(lc, nL)) -> net)
              //              trigger(PL_Send(p, Decide(lc, nL)) -> pl)
            )
        }
      }
    }
  }

  sequenceConsensus uponEvent {
    case SC_Propose(c) => handle {
      if (state == (LEADER, PREPARE)) {
        propCmds ++= List(c);
      }
      else if (state == (LEADER, ACCEPT)) {
        va ++= List(c);
        las(self) = las(self) + 1;
        pi.filter(p => p != self && lds.contains(p))
          .foreach(p =>
            trigger(NetMessage(self, p, Accept(nL, c)) -> net)
            //            trigger(PL_Send(p, Accept(nL, c)) -> pl)
          )
      }
    }
  }

}
