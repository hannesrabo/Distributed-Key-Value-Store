package se.kth.id2203

import se.kth.id2203.networking.NetAddress
import se.sics.kompics.KompicsEvent
import se.sics.kompics.sl._

case class KV_Boot(topology: Set[NetAddress]) extends KompicsEvent

class KVBootPort extends Port {
  request[KV_Boot]
}
