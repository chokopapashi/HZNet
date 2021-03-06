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
    log_debug(s"HZSocketClient($hzSoConf)")

    import HZSocketControler.{logger => _, _}
    import hzSoConf._

    class SocketClientActor(staticDataBuilder: SocketIOStaticDataBuilder, name: String,
                            parent: ActorRef, nextBody: NextReceiver) extends Actor
    {
        log_trace(s"SocketClientActor($staticDataBuilder,$parent)")

        private implicit val actorName = ActorName("SocketClient", self)

        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
            case _: Exception => Stop
            case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
        }

        private var so_desc: HZSocketDescription = null
        private var ioActor: ActorRef = null
        private val actorStates = HZActorStates()

        override def preStart() {
            log_hzso_actor_trace("preStart")

            actorStates += ConnectorActor.start(hzSoConf.endPoint, hzSoConf.localSocketAddressOpt,
                                                hzSoConf.connTimeout, hzSoConf.reuseAddress)
            context.become(receiveConnecting)
        }
        override def postRestart(reason: Throwable): Unit = ()  /* Disable the call to preStart() after restarts. */

        var originReason: HZActorReason = null
        def stopClient1(reason: HZActorReason, stopedActorOpt: Option[ActorRef] = None) {
            log_hzso_actor_trace(s"stopClient1($reason,$stopedActorOpt)")
            if(reason != null) originReason = reason
            stopedActorOpt match {
                case Some(a) => {
                    actorStates -= a
                    if(ioActor == a) {
                        parent ! HZSocketIOStop(so_desc,reason,ioActor,self)
                        ioActor = null
                    }
                }
                case None => 
            }
            if(actorStates.isEmpty) {
                log_hzso_actor_trace("actorStates.isEmpty==true")
                exitNormaly(originReason,parent)
            } else {
                log_hzso_actor_trace(f"actorStates=${actorStates.size}%d")
                actorStates.foreach(_.actor ! HZStop())
                context.become(receiveExiting)
            }
        }

        def receiveConnecting: Actor.Receive = {
            case HZEstablished(so) => {
                /*
                 *  Only save the acquired Socket here.
                 *  SocketIOActor starts when Terminated message of ConnectorActor is received.
                 */
                log_hzso_actor_debug(s"receiveConnecting:HZEstablished($so%s)")
                so.setSoTimeout(hzSoConf.recvTimeout)
                so_desc = HZSocketDescription(so)
            }
            case HZStop() => {
                log_hzso_actor_debug("receiveConnecting:HZStop")
                exitNormaly(HZCommandStoped(),parent)
            }
            case HZStopWithReason(reason) => {
                log_hzso_actor_debug(s"receiveConnecting:HZStopWithReason($reason)")
                stopClient1(HZCommandStopedWithReason(reason))
            }
            case Terminated(stopedActor: ActorRef) => {
                /*
                 * Receive this message just followed by HZEstablished.
                 * Hence, Sender is ConnectorActor only.
                 */
                log_hzso_actor_debug(s"receiveConnecting:Terminated($stopedActor)")
                actorStates -= stopedActor
                ioActor = SocketIOActor.start(so_desc.so, staticDataBuilder,  name + ".SocketIO")(nextBody)
                actorStates += ioActor
                parent ! HZSocketIOStart(so_desc, ioActor, self)
                context.unbecome()
            }
            case reason: HZConnectTimeout => {
                log_hzso_actor_debug(s"receiveConnecting:HZConnectTimeout")
                stopClient1(HZNullReason, Some(sender))
            }
            case reason: HZErrorStoped => {
                /*
                 * Stop ConnectorActor when it receive an error reason.
                 */
                log_hzso_actor_debug(s"receiveConnecting:HZErrorStoped=$reason")
                stopClient1(reason, Some(sender))
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug(s"receiveConnecting:HZActorReason=$reason")
                actorStates.addReason(sender, reason)
            }
        }

        def receive = {
//            case dataReceived @ HZDataReceived(_) => {
//                log_debug("SocketClient:receive:HZDataReceived")
//                parent ! dataReceived
//            }
            case sendData @ HZDataSending(_) => {
                log_hzso_actor_debug("receive:HZDataSending")
//                actorStates.head.actor ! sendData
                ioActor ! sendData
            }
            case HZStop() => {
                log_hzso_actor_debug("receive:HZStop")
                stopClient1(HZCommandStoped())
            }
            case HZStopWithReason(reason) => {
                log_hzso_actor_debug(s"receive:HZStopWithReason($reason)")
                stopClient1(HZCommandStopedWithReason(reason))
            }
            case Terminated(stopedActor: ActorRef) => {
                log_hzso_actor_debug(s"receive:Terminated($stopedActor)")
                stopClient1(HZNullReason, Some(stopedActor))
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug(s"receive:HZActorReason=$reason")
                actorStates.addReason(sender, reason)
            }
            case x => {
                log_hzso_actor_debug(s"receive:$x")
            }
        }

        def receiveExiting: Actor.Receive = {
            case Terminated(stopedActor: ActorRef) => {
                log_hzso_actor_debug(s"receiveExiting:Terminated($stopedActor %s)")
                stopClient1(HZNullReason, Some(stopedActor))
                actorStates -= stopedActor
                if(actorStates.isEmpty)
                    exitNormaly(originReason, parent)
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug(s"receive:HZActorReason=$reason")
                actorStates.addReason(sender, reason)
            }
            case x => log_hzso_actor_debug(s"receiveExiting:$x")
        }
    }
    object SocketClientActor {
        def start(staticDataBuilder: SocketIOStaticDataBuilder,
                  name: String = "SocketClientActor")
                 (nextBody: NextReceiver)
                 (implicit parent: ActorRef, context: ActorRefFactory): ActorRef
        = {
            log_debug(s"SocketClientActor:start($staticDataBuilder,$name)($parent,$context)")
            context.actorOf(Props(new SocketClientActor(staticDataBuilder, name, parent,nextBody)), name)
        }
    }
}

object HZSocketClient {
    implicit val logger = getLogger(this.getClass.getName)

    import HZSocketControler.{logger => _, _}

    def startSocketClient(hzSoConf: HZSoClientConf,
                          staticDataBuilder: SocketIOStaticDataBuilder,
                          name:String = "SocketClientActor")
                         (nextBody: NextReceiver)
                         (implicit parent: ActorRef, context: ActorRefFactory): ActorRef
    = {
        log_debug(s"startSocketClient($hzSoConf,$staticDataBuilder,$name)($parent,$context)")
        HZSocketClient(hzSoConf).SocketClientActor.start(staticDataBuilder, name)(nextBody)(parent, context)
    }
}

