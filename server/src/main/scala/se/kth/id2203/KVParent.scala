package se.kth.id2203

import se.kth.id2203.networking.NetAddress
import se.sics.kompics.sl.{ComponentDefinition, MatchedHandler}

class KVParent extends ComponentDefinition {

  val kvBootPort = provides[KVBootPort]

  kvBootPort uponEvent {
    case KV_Boot(topology: Set[NetAddress]) => handle {

    }
  }
}
