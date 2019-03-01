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
package se.kth.id2203;

import se.kth.id2203.bootstrapping._
import se.kth.id2203.consensus.{BallotLeaderElection, GossipLeaderElection, SequenceConsensus, SequencePaxos}
import se.kth.id2203.kvstore.KVService
import se.kth.id2203.networking.NetAddress
import se.kth.id2203.overlay._
import se.sics.kompics.sl._
import se.sics.kompics.Init
import se.sics.kompics.network.Network
import se.sics.kompics.timer.Timer;

class ParentComponent extends ComponentDefinition {

  //******* Ports ******
  val net = requires[Network]
  val timer = requires[Timer]
  //******* Children ******
  val boot = cfg.readValue[NetAddress]("id2203.project.bootstrap-address") match {
    case Some(_) => create(classOf[BootstrapClient], Init.NONE) // start in client mode
    case None => create(classOf[BootstrapServer], Init.NONE) // start in server mode
  }

  def afterBoot(topology: Set[NetAddress]) = {
    val self = cfg.getValue[NetAddress]("id2203.project.address")

    val kv = create(classOf[KVService], Init.NONE)
    val consensus = create(classOf[SequencePaxos], Init[SequencePaxos](self, topology))
    val gossipLeaderElection = create(classOf[GossipLeaderElection], Init[GossipLeaderElection](self, topology))

    // BallotLeaderElection (for paxos)
//    connect[Timer](timer -> gossipLeaderElection)
//    connect[Network](net -> gossipLeaderElection)
//
//    // Paxos
//    connect[BallotLeaderElection](gossipLeaderElection -> consensus)
//    connect[Network](net -> consensus)

    // KV (the actual thing)
    connect(Routing)(overlay -> kv)
    connect[Network](net -> kv)
//    connect[SequenceConsensus](consensus -> kv)
  }

  val overlay = create(classOf[VSOverlayManager], Init[VSOverlayManager](afterBoot))

  {
    // Startup procedures
    connect[Timer](timer -> boot)
    connect[Network](net -> boot)

    // Overlay/Routing of messages
    connect(Bootstrapping)(boot -> overlay)
    connect[Network](net -> overlay)
  }
}
