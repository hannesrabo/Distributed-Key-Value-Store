package se.kth.id2203.simulation

import se.kth.id2203.consensus.{RSM_Command, SC_Decide, SC_Propose, SequenceConsensus}
import se.kth.id2203.networking.NetAddress
import se.sics.kompics.sl.simulator.SimulationResult
import se.sics.kompics.sl.{ComponentDefinition, Init, handle}

import scala.util.Random

trait ProposedOpTrait extends RSM_Command {
  def command: String
}

case class ProposedOperation(command: String) extends ProposedOpTrait

class ConsensusClient(init: Init[ConsensusClient]) extends ComponentDefinition {

  private val consensus = requires[SequenceConsensus]

  private val self = init match {
    case Init(self: Int) => self
  }

  private val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
  private val size = alpha.length()

  private def randStr(n: Int) = (1 to n).map(_ => alpha(Random.nextInt.abs % size)).mkString

  private var proposals: List[String] = (for (i <- 0 to 10) yield randStr(i + 5)).toList
  SimulationResult += (s"prop:$self" -> proposals)

  def addCommand(command: String): Unit = {
    var currentList: List[String] = SimulationResult.get(s"res:$self").getOrElse(List.empty[String])
    currentList = currentList ::: List(command)
    SimulationResult += (s"res:$self" -> currentList)
  }

  def sendCommand(): Unit = {
    val currentCommand: String = proposals.head
    proposals = proposals.tail
    trigger(SC_Propose(ProposedOperation(currentCommand)) -> consensus)
  }

  consensus uponEvent {
    case SC_Decide(ProposedOperation(command: String)) => handle {
      addCommand(command)
      if (proposals.nonEmpty) {
        sendCommand()
      }
    }
  }
}
