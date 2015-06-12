/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Props
import akka.actor.Terminated
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy.Stop
import akka.actor.SupervisorStrategy.Escalate

import org.hirosezouen.hzutil.HZLog._
import org.hirosezouen.hzactor.HZActor._

case class HZSocketClient(hzSoConf: HZSoClientConf)
{
    implicit val logger = getLogger(this.getClass.getName)
    log_debug("HZSocketClient(%s)".format(hzSoConf))

    import HZSocketControler.{logger => _, _}
    import hzSoConf._

    class SocketClientActor(staticDataBuilder: SocketIOStaticDataBuilder, parent: ActorRef,
                            nextReceive: NextReceiver) extends Actor
    {
        log_trace("SocketClientActor(%s,%s)".format(staticDataBuilder,parent))

        private implicit val actorName = ActorName("SocketClient", self)

        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
            case _: Exception => Stop
            case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
        }

        override def preStart() {
            log_hzso_actor_debug()
        }

        private var so_desc: HZSocketDescription = null
        private var connectActor: ActorRef = null
        private var ioActor: ActorRef = null
        private var actorStateSet = Set.empty[HZActorState]

        context.become(receiveConnecting)
        connectActor = ConnectorActor.start(hzSoConf.endPoint, hzSoConf.localSocketAddressOpt,
                                            hzSoConf.connTimeout, hzSoConf.reuseAddress, self)
        actorStateSet += HZActorState(connectActor)

        var originReason: HZActorReason = null
        def stopClient1(reason: HZActorReason, stopedActorOpt: Option[ActorRef] = None) {
            log_hzso_actor_trace("stopClient1(%s,%s)".format(reason,stopedActorOpt))
            if(reason != null) originReason = reason
            stopedActorOpt match {
                case Some(a) => {
                    actorStateSet = actorStateSet.filterNot(_.actor == a)
                    if(ioActor == a) {
                        parent ! HZIOStop(so_desc,reason,ioActor,self)
                        ioActor = null
                    }
                }
                case None => 
            }
            if(actorStateSet.isEmpty) {
                log_hzso_actor_trace("actorStateSet.isEmpty==true")
                exitNormaly(originReason,parent)
            } else {
                log_hzso_actor_trace("actorStateSet=%d".format(actorStateSet.size))
                actorStateSet.foreach(_.actor ! HZStop())
                context.become(receiveExiting)
            }
        }

        def receiveConnecting: Actor.Receive = {
            case HZEstablished(so) => {
                log_hzso_actor_debug("receiveConnecting:HZEstablished(%s)".format(so))
                so.setSoTimeout(hzSoConf.recvTimeout)
                ioActor = SocketIOActor.start(so, staticDataBuilder, self)(nextReceive)
                actorStateSet += HZActorState(ioActor)
                so_desc = HZSocketDescription(so)
                parent ! HZIOStart(so_desc, ioActor, self)
                context.unbecome()
            }
            case HZStop() => {
                log_hzso_actor_debug("receiveConnecting:HZStop")
                exitNormaly(HZCommandStoped(),parent)
            }
            case HZStopWithReason(reason) => {
                log_hzso_actor_debug("receiveConnecting:HZStopWithReason(%s)".format(reason))
                stopClient1(HZCommandStopedWithReason(reason))
            }

            case Terminated(stopedActor: ActorRef) => {
                log_hzso_actor_debug("receiveConnecting:Terminated(%s)".format(stopedActor))
                if(connectActor == stopedActor) {
                    actorStateSet = actorStateSet.filterNot(_.actor == stopedActor)
                    connectActor = null
                } else {
                    stopClient1(HZNullReason, Some(stopedActor))
                }
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug("receiveConnecting:HZActorReason=%s".format(reason))
                actorStateSet = actorStateSet.map(as => if(as.actor == sender) HZActorState(as.actor, reason) else as)
            }
        }

        def receive = {
//            case dataReceived @ HZDataReceived(_) => {
//                log_debug("SocketClient:receive:HZDataReceived")
//                parent ! dataReceived
//            }
            case sendData @ HZDataSending(_) => {
                log_hzso_actor_debug("receive:HZDataSending")
                actorStateSet.head.actor ! sendData
            }
            case HZStop() => {
                log_hzso_actor_debug("receive:HZStop")
                stopClient1(HZCommandStoped())
            }
            case HZStopWithReason(reason) => {
                log_hzso_actor_debug("receive:HZStopWithReason(%s)".format(reason))
                stopClient1(HZCommandStopedWithReason(reason))
            }
            case Terminated(stopedActor: Actor) => {
                log_hzso_actor_debug("receive:Terminated(%s)".format(stopedActor))
                stopClient1(HZNullReason, Some(stopedActor))
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug("receive:HZActorReason=%s".format(reason))
                actorStateSet = actorStateSet.map(as => if(as.actor == sender) HZActorState(as.actor, reason) else as)
            }
            case x => {
                log_hzso_actor_debug("receive:%s".format(x))
            }
        }

        def receiveExiting: Actor.Receive = {
            case Terminated(stopedActor: Actor) => {
                log_hzso_actor_debug("receiveExiting:Terminated(%s)".format(stopedActor))
                stopClient1(HZNullReason, Some(stopedActor))
                actorStateSet = actorStateSet.filterNot(_.actor == stopedActor)
                if(actorStateSet.isEmpty)
                    exitNormaly(originReason,parent)
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug("receive:HZActorReason=%s".format(reason))
                actorStateSet = actorStateSet.map(as => if(as.actor == sender) HZActorState(as.actor, reason) else as)
            }
            case x => log_hzso_actor_debug("receiveExiting:%s".format(x))
        }
    }
    object SocketClientActor {
        def start(staticDataBuilder: SocketIOStaticDataBuilder,
                  parent: ActorRef)
                 (nextBody: NextReceiver)
                 (implicit context: ActorRefFactory): ActorRef
        = {
            log_debug("SocketClientActor:start(%s,%s)".format(staticDataBuilder,parent))
            context.actorOf(Props(new SocketClientActor(staticDataBuilder,parent,nextBody)), "SocketClientActor")
        }
    }
}

object HZSocketClient {
    implicit val logger = getLogger(this.getClass.getName)

    import HZSocketControler.{logger => _, _}

    def startSocketClient(hzSoConf: HZSoClientConf,
                          staticDataBuilder: SocketIOStaticDataBuilder,
                          parent: ActorRef)
                         (nextBody: NextReceiver)
                         (implicit context: ActorRefFactory): ActorRef
    = {
        log_debug("startSocketClient(%s,%s,%s)".format(hzSoConf,staticDataBuilder,parent))
        HZSocketClient(hzSoConf).SocketClientActor.start(staticDataBuilder, parent)(nextBody)(context)
    }
}

