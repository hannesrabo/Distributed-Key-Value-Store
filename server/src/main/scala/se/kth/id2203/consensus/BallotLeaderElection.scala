package se.kth.id2203.consensus

import se.sics.kompics.network._
import se.sics.kompics.sl._
import se.sics.kompics.{KompicsEvent}

case class BLE_Leader(leader: Address, ballot: Long) extends KompicsEvent;

class BallotLeaderElection extends Port {
  indication[BLE_Leader];
}