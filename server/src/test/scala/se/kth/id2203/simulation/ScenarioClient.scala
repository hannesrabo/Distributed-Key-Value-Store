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
package se.kth.id2203.simulation

import java.util.UUID

import se.kth.id2203.kvstore._
import se.kth.id2203.networking._
import se.kth.id2203.overlay.RouteMsg
import se.sics.kompics.sl._
import se.sics.kompics.Start
import se.sics.kompics.network.Network
import se.sics.kompics.timer.Timer
import se.sics.kompics.sl.simulator.SimulationResult

import collection.mutable
import collection.JavaConverters._

class ScenarioClient extends ComponentDefinition {

  //******* Ports ******
  val net = requires[Network]
  val timer = requires[Timer]

  //******* Fields ******
  val self = cfg.getValue[NetAddress]("id2203.project.address")
  val server = cfg.getValue[NetAddress]("id2203.project.bootstrap-address")
  var pending: Option[Operation] = None
  var op_index = 0
  var Ops = List.empty[Operation]

  //******* Handlers ******
  ctrl uponEvent {
    case _: Start => handle {
      val op_type: String = SimulationResult[String]("operations")
      val nMessages = SimulationResult[Int]("nMessages")

      if (op_type == "NOP" ) {
        for (i <- 0 to nMessages) {
          Ops ++= List(new se.kth.id2203.kvstore.Op(s"test$i"))
        }
      } else if (op_type == "ReadWrite" ) {
        for (i <- 0 to nMessages) {
          Ops ++= List(new Write(s"test$i", s"$i"))
          Ops ++= List(new Read(s"test$i"))
        }
      }

      sendMessage()
    }
  }

  def sendMessage() = {
    val op = Ops(op_index)
    op_index = op_index + 1
    pending = Some(op)
    val routeMsg = RouteMsg(op.key, op) // don't know which partition is responsible, so ask the bootstrap server to forward it
    trigger(NetMessage(self, server, routeMsg) -> net)

    logger.info("Sending {}", op)
    SimulationResult += (op.key -> "Sent")
  }

  net uponEvent {
    case NetMessage(header, or @ OpResponse(id, status, value)) => handle {
      logger.debug(s"Got OpResponse: $or")

      if (op_index < Ops.size) {
        sendMessage()
      }

      if (pending.isDefined && pending.get.id == id) {
        SimulationResult += (pending.get.key -> (s"${status.toString()}", value))

      } else {
        logger.warn("ID $id was not pending! Ignoring response.")
      }
    }
  }
}
