/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import java.io.IOException
import java.net.ServerSocket

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.Exception._

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

case class HZSocketServer(hzSoConf: HZSoServerConf)
{
    implicit val logger = getLogger(this.getClass.getName)
    log_debug(s"HZSocketServer($hzSoConf)")

    import HZSocketControler.{logger => _, _}
    import hzSoConf._

    class SocketServerActor(staticDataBuilder: SocketIOStaticDataBuilder, name: String,
                            parent: ActorRef, nextBody: NextReceiver) extends Actor
    {
        log_trace(s"SocketServerActor($staticDataBuilder,$parent)")

        private implicit val actorName = ActorName("SocketServer", self)

        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
            case _: Exception => Stop
            case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
        }

        val serverSocket: ServerSocket = catching(classOf[IOException]) either {
            new ServerSocket(hzSoConf.port)
        } match {
            case Right(svso) => svso
            case Left(th) => {
                log_hzso_actor_error(f"new serverSocket(${hzSoConf.port}%d):Left($th)") 
                log_hzso_actor_debug(f"new serverSocket(${hzSoConf.port}%d):Left",th) 
                exitWithError(th, parent)
            }
        }

        private var ioActorMap = Map.empty[ActorRef,HZSocketDescription]
        private lazy val acceptActor = AccepterActor.start(serverSocket, hzSoConf.acceptTimeout)
        private val actorStates = HZActorStates()

        override def preStart() {
            log_hzso_actor_trace("preStart")
            actorStates += acceptActor
        }
        override def postRestart(reason: Throwable): Unit = ()  /* Disable the call to preStart() after restarts. */

        def stopSocket1(reason: HZActorReason , stopedActorOpt: Option[ActorRef] = None) {
            log_hzso_actor_trace(s"stopSocket1($reason,$stopedActorOpt)") 
            stopedActorOpt match {
                case Some(a) => {
                    actorStates -= a
                    ioActorMap.get(a) match {
                        case Some(so_desc) => {
                            log_hzso_actor_trace(s"stopSocket1:ioActorMap.get:Some($so_desc)") 
                            parent ! HZSocketIOStop(so_desc, reason, a, self)
                            ioActorMap -= a
                        }
                        case None => {
                            log_hzso_actor_error(s"stopSocket1:ioActorMap.get:None:stopedActor=$a") 
                        }
                    }
                }
                case None => 
            }
        }

        var originReason: HZActorReason = null
        def stopServer1(reason: HZActorReason, stopedActorOpt: Option[ActorRef] = None) {
            log_hzso_actor_trace(s"stopServer1($reason,$stopedActorOpt)")
            serverSocket.close()
            if(reason != null) originReason = reason
            stopedActorOpt match {
                case Some(a) => actorStates -= a
                case None => 
            }
            if(actorStates.isEmpty) {
                log_hzso_actor_trace("stopServer1:actorStates.isEmpty==true")
                exitNormaly(originReason,parent)
            } else {
                log_hzso_actor_trace(f"stopServer1:actorStates.size=${actorStates.size}%d")
                actorStates.foreach(_.actor ! HZStop())
                context.become(receiveExiting)
            }
        }

        def isConnectionFull(): Boolean = {
            hzSoConf.maxConn match {
                case 0 => false
                case x if(ioActorMap.size < x) => true
                case _ => false
            }
        }

        def receive = {
//            case dataReceived @ HZDataReceived(_) => {
//                log_debug("SocketServer:receive:HZDataReceived")
//                parent ! dataReceived
//            }
//            case sendData @ HZDataSending(_) => {
//                log_debug("SocketServer:receive:HZDataSending")
//                socketActor ! sendData
//            }
            case HZAccepted(so) => {
                log_hzso_actor_debug(s"receive:HZAccepted($so)")
                if(isConnectionFull()) {
                    log_hzso_actor_error("The number of client connections has reached the upper limit.")
                    log_hzso_actor_trace("receive:HZAccepted:isConnectionFull = true:" +
                                         f"hzSoConf.maxConn=${hzSoConf.maxConn}%d,ioActorMap.size=${ioActorMap.size}%d")
                    so.close()
                } else {
                    log_hzso_actor_trace("receive:HZAccepted:isConnectionFull = false")
                    catching(classOf[IOException]) either {
                        so.setSoTimeout(hzSoConf.recvTimeout)
                    } match {
                        case Right(_) => /* Ok, Nothing to do. */
                        case Left(th) => {
                            log_hzso_actor_error(s"so.setSoTimeout:Left($th)") 
                            log_hzso_actor_debug("so.setSoTimeout:Left",th)
                            stopServer1(HZErrorStoped(th))
                        }
                    }
                    val ioActor = SocketIOActor.start(so, staticDataBuilder, name + ".SocketIO")(nextBody)
                    actorStates += ioActor
                    val so_desc = HZSocketDescription(so)
                    ioActorMap += (ioActor -> so_desc)
                    parent ! HZSocketIOStart(so_desc, ioActor, self)
                }
            }
            case HZStop() => {
                log_hzso_actor_debug("receive:HZStop")
                stopServer1(HZCommandStoped())
            }
            case HZStopWithReason(reason) => {
                log_hzso_actor_debug(s"receive:HZStopWithReason($reason)")
                stopServer1(HZCommandStopedWithReason(reason))
            }
            case Terminated(stopedActor: ActorRef) => {
                log_hzso_actor_debug(s"receive:Terminated($stopedActor)")
                if(stopedActor == acceptActor) {
                    log_hzso_actor_trace(s"receive:Terminated:stopedActor==acceptActor:$stopedActor")
                    stopServer1(HZNullReason,Some(stopedActor))
                } else {
                    log_hzso_actor_trace(s"receive:Terminated:stopedActor!=acceptActor:$stopedActor!=$acceptActor")
                    stopSocket1(HZNullReason,Some(stopedActor))
                }
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
                log_hzso_actor_debug(s"receiveExiting:Terminated($stopedActor)")
                actorStates -= stopedActor
                if(actorStates.isEmpty)
                    exitNormaly(originReason,parent)
            }
            case x => log_hzso_actor_debug(s"loopExiting:$x")
        }
    }

    object SocketServerActor {
        def start(staticDataBuilder: SocketIOStaticDataBuilder,
                  name: String = "SocketServerActor")
                 (nextBody: NextReceiver)
                 (implicit parent: ActorRef, context: ActorRefFactory): ActorRef
        = {
            log_debug(s"SocketServer:start($staticDataBuilder,$name)($parent,$context)")
            context.actorOf(Props(new SocketServerActor(staticDataBuilder,name,parent,nextBody)), name)
        }
    }
}

object HZSocketServer {
    implicit val logger = getLogger(this.getClass.getName)

    import org.hirosezouen.hznet.{HZSocketControler => hzso}

    def startSocketServer(hzSoConf: HZSoServerConf,
                          staticDataBuilder: SocketIOStaticDataBuilder,
                          name: String = "SocketServerActor")
                         (nextBody: hzso.NextReceiver)
                         (implicit parent: ActorRef, context: ActorRefFactory): ActorRef
    = {
        log_debug(s"startSocketServer($hzSoConf,$staticDataBuilder,$name)($parent,$context)")
        HZSocketServer(hzSoConf).SocketServerActor.start(staticDataBuilder, name)(nextBody)(parent,context)
    }
}

