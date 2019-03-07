package se.kth.id2203.simulation

import se.kth.id2203.consensus.{RSM_Command, SC_Decide, SC_Propose, SequenceConsensus}
import se.kth.id2203.networking.{NetAddress, NetMessage}
import se.sics.kompics.{KompicsEvent, Start}
import se.sics.kompics.network.Network
import se.sics.kompics.sl.simulator.SimulationResult
import se.sics.kompics.sl.{ComponentDefinition, Init, handle}
import se.sics.kompics.timer.{ScheduleTimeout, Timeout, Timer}

import scala.util.Random

trait ProposedOpTrait extends RSM_Command {
  def command: String
}

case class NET_Propose(command: String) extends KompicsEvent
case class CheckTimeout(timeout: ScheduleTimeout) extends Timeout(timeout)

case class ProposedOperation(command: String) extends ProposedOpTrait

class ConsensusClient(init: Init[ConsensusClient]) extends ComponentDefinition {

  private val consensus = requires[SequenceConsensus]
  private val net = requires[Network]
  private val timer = requires[Timer]

  private val (self, selfAddr, topology) = init match {
    case Init(self: Int, selfAddr: NetAddress, topology: Set[NetAddress]@unchecked) => (self, selfAddr, topology)
  }

  private val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  private val size = alpha.length()
  private val nMessages = 1

  private def randStr(n: Int) = (1 to n).map(_ => alpha(Random.nextInt.abs % size)).mkString

  private var proposals: List[String] = (for (i <- 0 to nMessages) yield randStr(i + 5)).toList

  var count: Int = 0
  def addCommand(command: String): Unit = {
    SimulationResult += (s"res:$self:$count" -> command)
    count += 1
    SimulationResult += (s"res:$self" -> count.toString())
//    if (self > 17)
//      println((s"res:$self:$count" -> command))
  }

  def sendCommand(): Unit = {
    val currentCommand: String = proposals.head
    proposals = proposals.tail

    topology.foreach(node => {
      trigger(NetMessage(selfAddr, node, NET_Propose(currentCommand)) -> net);
      println(s"[$self] Sending Net Message to ${node.getIp()}")
      if(node.getIp().toString == "192.193.0.19")
          println(s"$self: $currentCommand")
    })

    // Send next command
    if (proposals.nonEmpty) {
      startTimer(Random.nextInt(2000))
    }
  }

  private def startTimer(delay: Long): Unit = {
    val scheduledTimeout = new ScheduleTimeout(delay)
    scheduledTimeout.setTimeoutEvent(CheckTimeout(scheduledTimeout))
    trigger(scheduledTimeout -> timer)
  }


  ctrl uponEvent {
    case _: Start => handle {
      println(s"Node: $self, Topology Size: ${topology.size}, Topology: $topology")

      SimulationResult += (s"prop:$self" -> proposals.size.toString())
      var i = 0
      for(proposal: String <- proposals) {
        SimulationResult += (s"prop:$self:$i" -> proposal)
        i += 1
      }
      startTimer(20000)
    }
  }

  net uponEvent {
    case NetMessage(header, NET_Propose(command)) => handle {
      trigger(SC_Propose(ProposedOperation(command)) -> consensus)
    }
  }

  timer uponEvent {
    case CheckTimeout(_) => handle {
      sendCommand()
    }
  }

  consensus uponEvent {
    case SC_Decide(ProposedOperation(command: String)) => handle {
      addCommand(command)
    }
  }
}
