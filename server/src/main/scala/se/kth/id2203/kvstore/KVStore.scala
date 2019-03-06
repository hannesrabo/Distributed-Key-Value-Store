/*
 * The MIT License
 *
 * Copyright 2017 Lars Kroll <lkroll@kth.se>.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package se.kth.id2203.kvstore

import se.kth.id2203.consensus.{RSM_Command, SC_Decide, SC_Propose, SequenceConsensus}
import se.kth.id2203.epfd.{CorrectSystem, EventuallyPerfectFailureDetector, FaultySystem}
import se.kth.id2203.networking._
import se.kth.id2203.overlay.Routing
import se.sics.kompics.sl._
import se.sics.kompics.network.Network

import scala.collection.mutable

trait ProposedOpTrait extends RSM_Command {
  def sender: NetAddress

  def command: Operation
}

case class ProposedOperation(sender: NetAddress, command: Operation) extends ProposedOpTrait

class KVService extends ComponentDefinition {

  //******* Ports ******
  val net: PositivePort[Network] = requires[Network]
  val consensus = requires[SequenceConsensus]
  val epfd = requires[EventuallyPerfectFailureDetector]

  //******* Fields ******
  private val self = cfg.getValue[NetAddress]("id2203.project.address")
  private val storage = mutable.Map.empty[String, String]
  private var correctGroup = true

  //******* Handlers ******
  net uponEvent {
    case NetMessage(header, op: Operation) => handle {
      log.info("Got operation {}!", op)
      if (correctGroup)
        trigger(SC_Propose(ProposedOperation(header.src, op)) -> consensus)
      else
        trigger(NetMessage(self, header.src, op.response(OpCode.NotAvailable)) -> net)
    }
  }

  epfd uponEvent {
    case CorrectSystem() => handle {
      correctGroup = true
    }
    case FaultySystem() => handle {
      correctGroup = false
    }
  }

  // The decided upon messages
  consensus uponEvent {
    case SC_Decide(ProposedOperation(sender: NetAddress, command: Op)) => handle {
      log.info(s"(Not) Handling operation {}!", command)
      trigger(NetMessage(self, sender, command.response(OpCode.NotImplemented)) -> net)
    }

    case SC_Decide(ProposedOperation(sender: NetAddress, command: Read)) => handle {
      log.info(s"Handling operation {}!", command)
      trigger(NetMessage(self, sender, command.response(OpCode.Ok, storage.get(command.key))) -> net)
    }

    case SC_Decide(ProposedOperation(sender: NetAddress, command: Write)) => handle {
      log.info(s"Handling operation {}!", command)
      storage += (command.key -> command.value)
      trigger(NetMessage(self, sender, command.response(OpCode.Ok)) -> net)
    }

    case SC_Decide(ProposedOperation(sender: NetAddress, command: CompareAndSwap)) => handle {
      log.info(s"Handling operation {}!", command)
      val result = storage.get(command.key) match {
        case Some(value) => {
          // Only perform the operation if it is the same
          if (command.expected.isDefined && command.expected.get == value) {
            storage(command.key) = command.value
          }

          Some(value)
        }
        case None => {
          // Only add if it is expected to be empty
          if (command.expected.isEmpty) {
            storage += (command.key -> command.value)
          }

          None
        }
      }

      trigger(NetMessage(self, sender, command.response(OpCode.Ok, result)) -> net)
    }
  }
}
