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
    log_debug("HZSocketServer(%s)".format(hzSoConf))

    import HZSocketControler.{logger => _, _}
    import hzSoConf._

    class SocketServerActor(staticDataBuilder: SocketIOStaticDataBuilder, parent: ActorRef,
                            nextReceive: NextReceiver) extends Actor
    {
        log_trace("SocketServerActor(%s,%s)".format(staticDataBuilder,parent))

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
                log_hzso_actor_error("new serverSocket(%d):Left(%s)".format(hzSoConf.port,th)) 
                log_hzso_actor_debug("new serverSocket(%d):Left".format(hzSoConf.port),th) 
                exitWithError(th, parent)
            }
        }

        private var ioActorMap = Map.empty[ActorRef,HZSocketDescription]
        private lazy val acceptActor = AccepterActor.start(serverSocket, hzSoConf.acceptTimeout, self)
        private val actorStates = HZActorStates()

        override def preStart() {
            log_hzso_actor_trace("preStart")
            actorStates += acceptActor
        }
        override def postRestart(reason: Throwable): Unit = ()  /* Disable the call to preStart() after restarts. */

        def stopSocket1(reason: HZActorReason , stopedActorOpt: Option[ActorRef] = None) {
            log_hzso_actor_trace("stopSocket1(%s,%s)".format(reason,stopedActorOpt)) 
            stopedActorOpt match {
                case Some(a) => {
                    actorStates -= a
                    ioActorMap.get(a) match {
                        case Some(so_desc) => {
                            log_hzso_actor_trace("stopSocket1:ioActorMap.get:Some(%s)".format(so_desc)) 
                            parent ! HZIOStop(so_desc, reason, a, self)
                            ioActorMap -= a
                        }
                        case None => {
                            log_hzso_actor_error("stopSocket1:ioActorMap.get:None:stopedActor=%s".format(a)) 
                        }
                    }
                }
                case None => 
            }
        }

        var originReason: HZActorReason = null
        def stopServer1(reason: HZActorReason, stopedActorOpt: Option[ActorRef] = None) {
            log_hzso_actor_trace("stopServer1(%s,%s)".format(reason,stopedActorOpt))
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
                log_hzso_actor_trace("stopServer1:actorStates.size=%d".format(actorStates.size))
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
                log_hzso_actor_debug("receive:HZAccepted(%s)".format(so))
                if(isConnectionFull()) {
                    log_hzso_actor_error("The number of client connections has reached the upper limit.")
                    log_hzso_actor_trace(("receive:HZAccepted:isConnectionFull = true:" +
                                          "hzSoConf.maxConn=%d,ioActorMap.size=%d").format(
                                          hzSoConf.maxConn, ioActorMap.size))
                    so.close()
                } else {
                    log_hzso_actor_trace("receive:HZAccepted:isConnectionFull = false")
                    catching(classOf[IOException]) either {
                        so.setSoTimeout(hzSoConf.recvTimeout)
                    } match {
                        case Right(_) => /* Ok, Nothing to do. */
                        case Left(th) => {
                            log_hzso_actor_error("so.setSoTimeout:Left(%s)".format(th)) 
                            log_hzso_actor_debug("so.setSoTimeout:Left",th)
                            stopServer1(HZErrorStoped(th))
                        }
                    }
                    val ioActor = SocketIOActor.start(so, staticDataBuilder, self)(nextReceive)
                    actorStates += ioActor
                    val so_desc = HZSocketDescription(so)
                    ioActorMap += (ioActor -> so_desc)
                    parent ! HZIOStart(so_desc, ioActor, self)
                }
            }
            case HZStop() => {
                log_hzso_actor_debug("receive:HZStop")
                stopServer1(HZCommandStoped())
            }
            case HZStopWithReason(reason) => {
                log_hzso_actor_debug("receive:HZStopWithReason(%s)".format(reason))
                stopServer1(HZCommandStopedWithReason(reason))
            }
            case Terminated(stopedActor: ActorRef) => {
                log_hzso_actor_debug("receive:Terminated(%s)".format(stopedActor))
                if(stopedActor == acceptActor) {
                    log_hzso_actor_trace("receive:Terminated:stopedActor==acceptActor:%s".format(stopedActor))
                    stopServer1(HZNullReason,Some(stopedActor))
                } else {
                    log_hzso_actor_trace("receive:Terminated:stopedActor!=acceptActor:%s!=%s".format(stopedActor,acceptActor))
                    stopSocket1(HZNullReason,Some(stopedActor))
                }
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug("receive:HZActorReason=%s".format(reason))
                actorStates.addReason(sender, reason)
            }
            case x => {
                log_hzso_actor_debug("receive:%s".format(x))
            }
        }

        def receiveExiting: Actor.Receive = {
            case Terminated(stopedActor: ActorRef) => {
                log_hzso_actor_debug("receiveExiting:Terminated(%s)".format(stopedActor))
                actorStates -= stopedActor
                if(actorStates.isEmpty)
                    exitNormaly(originReason,parent)
            }
            case x => log_hzso_actor_debug("loopExiting:%s".format(x))
        }
    }

    object SocketServerActor {
        def start(staticDataBuilder: SocketIOStaticDataBuilder,
                  parent: ActorRef)
                 (nextBody: NextReceiver)
                 (implicit context: ActorRefFactory): ActorRef
        = {
            log_debug("SocketServer:start(%s,%s)".format(staticDataBuilder,parent))
            context.actorOf(Props(new SocketServerActor(staticDataBuilder,parent,nextBody)), "SocketServerActor")
        }
    }
}

object HZSocketServer {
    implicit val logger = getLogger(this.getClass.getName)

    import org.hirosezouen.hznet.{HZSocketControler => hzso}

    def startSocketServer(hzSoConf: HZSoServerConf,
                          staticDataBuilder: SocketIOStaticDataBuilder,
                          parent: ActorRef)
                         (nextBody: hzso.NextReceiver)
                         (implicit context: ActorRefFactory): ActorRef
    = {
        log_debug("startSocketServer(%s,%s,%s)".format(hzSoConf,staticDataBuilder,parent))
        HZSocketServer(hzSoConf).SocketServerActor.start(staticDataBuilder, parent)(nextBody)(context)
    }
}

