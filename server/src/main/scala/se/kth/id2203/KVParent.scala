package se.kth.id2203

import se.kth.id2203.bootstrapping.{Booted, Bootstrapping}
import se.kth.id2203.consensus.{BallotLeaderElection, GossipLeaderElection, SequenceConsensus, SequencePaxos}
import se.kth.id2203.kvstore.KVService
import se.kth.id2203.networking.NetAddress
import se.kth.id2203.overlay.{LookupTable, Routing}
import se.sics.kompics.Component
import se.sics.kompics.network.Network
import se.sics.kompics.sl.{ComponentDefinition, Init, PositivePort, handle}
import se.sics.kompics.timer.Timer

class KVParent extends ComponentDefinition {

  val boot: PositivePort[Bootstrapping.type] = requires(Bootstrapping)
  val net: PositivePort[Network] = requires[Network]
  val timer: PositivePort[Timer] = requires[Timer]
  val overlay: PositivePort[Routing.type] = requires(Routing)

  boot uponEvent {
    case Booted(assignment: LookupTable) => handle {
      val self = cfg.getValue[NetAddress]("id2203.project.address")
      val topology: Set[NetAddress] = assignment.lookupSelf(self)

      val kv = create(classOf[KVService], Init.NONE)
      val consensus = create(classOf[SequencePaxos], Init[SequencePaxos](self, topology))
      val gossipLeaderElection = create(classOf[GossipLeaderElection], Init[GossipLeaderElection](self, topology))

      // BallotLeaderElection (for paxos)
      connect[Timer](timer -> gossipLeaderElection)
      connect[Network](net -> gossipLeaderElection)

      // Paxos
      connect[BallotLeaderElection](gossipLeaderElection -> consensus)
      connect[Network](net -> consensus)

      // KV (the actual thing)
      connect(Routing)(overlay.asInstanceOf[Component] -> kv)
      connect[Network](net -> kv)
      connect[SequenceConsensus](consensus -> kv)
    }
  }
}
