package se.kth.id2203.epfd

import se.sics.kompics.KompicsEvent
import se.sics.kompics.timer.{ScheduleTimeout, Timeout}

case class CheckTimeout(timeout: ScheduleTimeout) extends Timeout(timeout)

case class HeartbeatReply(seq: Int) extends KompicsEvent
case class HeartbeatRequest(seq: Int) extends KompicsEvent
