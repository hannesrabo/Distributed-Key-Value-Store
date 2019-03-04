package se.kth.id2203.simulation

import se.sics.kompics.network.Network
import se.sics.kompics.sl.ComponentDefinition

class ConsensusHost extends ComponentDefinition {

  val net = requires[Network]



}
