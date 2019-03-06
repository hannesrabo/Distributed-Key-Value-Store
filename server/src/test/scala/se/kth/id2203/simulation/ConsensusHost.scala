package se.kth.id2203.simulation

import se.kth.id2203.consensus.{BallotLeaderElection, GossipLeaderElection, SequenceConsensus, SequencePaxos}
import se.kth.id2203.networking.NetAddress
import se.sics.kompics.network.Network
import se.sics.kompics.sl.{ComponentDefinition, Init}
import se.sics.kompics.timer.Timer

class ConsensusHost(init: Init[ConsensusHost]) extends ComponentDefinition {

  val net = requires[Network]
  val timer = requires[Timer]

  val (self, addr, pi) = init match {
    case Init(self: Int, addr: NetAddress, pi: Set[NetAddress]@unchecked) => (self, addr, pi)
  }

  val consensus = create(classOf[SequencePaxos], Init[SequencePaxos](addr, pi))
  val consensusClient = create(classOf[ConsensusClient], Init[ConsensusClient](self))
  val gossipLeaderElection = create(classOf[GossipLeaderElection], Init[GossipLeaderElection](addr, pi))

  // BallotLeaderElection (for paxos)
  connect[Timer](timer -> gossipLeaderElection)
  connect[Network](net -> gossipLeaderElection)

  // Paxos
  connect[BallotLeaderElection](gossipLeaderElection -> consensus)
  connect[Network](net -> consensus)

  // ConsensusClient
  connect[SequenceConsensus](consensus -> consensusClient)
}
