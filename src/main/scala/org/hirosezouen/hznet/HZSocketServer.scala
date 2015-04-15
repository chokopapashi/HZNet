/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import java.io.IOException
import java.net.ServerSocket

import scala.actors._
import scala.actors.Actor._
import scala.util.control.Exception._

import org.hirosezouen.hzutil._
import HZActor._
import HZLog._

case class HZSocketServer(hzSoConf: HZSoServerConf)
{
    implicit val logger = getLogger(this.getClass.getName)
    log_debug("HZSocketServer(%s)".format(hzSoConf))

    import HZSocketControler.{logger => _, _}
    import hzSoConf._

    def startSocketServerActor(staticDataBuilder: SocketIOStaticDataBuilder, parent: Actor, linkParent: Boolean)
                              (nextReceive: NextReceiver): Actor =
    {
        log_debug("startSocketServerActor")

        actor {
            implicit val actorName = ActorName("SocketServer")
            log_hzso_actor_debug()

            if(linkParent) {
                log_hzso_actor_debug("link(%s)".format(parent))
                link(parent)
            } else {
                log_hzso_actor_debug("no link to parent")
            }
            self.trapExit = true

            val serverSocket: ServerSocket = catching(classOf[IOException]) either {
                new ServerSocket(hzSoConf.port)
            } match {
                case Right(svso) => svso
                case Left(th) => {
                    log_hzso_actor_error("new serverSocket(%d):Left(%s)".format(hzSoConf.port,th)) 
                    log_hzso_actor_debug("new serverSocket(%d):Left".format(hzSoConf.port),th) 
                    exit(HZErrorStoped(th))
                }
            }

            var acceptActor: Actor = null
            var actorSet = Set.empty[Actor]
            var ioActorMap = Map.empty[Actor,HZSocketDescription]
            var originReason: AnyRef = null
            var loopfunc: () => Unit = null

            def stopSocket1(reason: AnyRef, stopedActor: Actor) {
                log_hzso_actor_trace("stopSocket1(%s,%s)".format(reason,stopedActor)) 
                actorSet -= stopedActor
                ioActorMap.get(stopedActor) match {
                    case Some(so_desc) => {
                        log_hzso_actor_trace("stopSocket1:ioActorMap.get:Some(%s)".format(so_desc)) 
                        val ioActor = stopedActor
                        parent ! HZIOStop(so_desc, reason, ioActor, ioActor, self)
                        ioActorMap -= ioActor
                    }
                    case None => {
                        log_hzso_actor_error("stopSocket1:ioActorMap.get:None:stopedActor=%s".format(stopedActor)) 
                    }
                }
            }

            def stopServer1(reason: AnyRef, stopedActor: Actor = null) {
                log_hzso_actor_trace("stopServer1(%s,%s)".format(reason,stopedActor))
                serverSocket.close()
                if(reason != null) originReason = reason
                if(stopedActor != null) actorSet -= stopedActor
                if(actorSet.isEmpty) {
                    log_hzso_actor_trace("stopServer1:actorSet.isEmpty==true")
                    exit(originReason)
                } else {
                    log_hzso_actor_trace("stopServer1:actorSet=%d".format(actorSet.size))
                    actorSet.foreach(_ ! HZStop())
                    loopfunc = loopExiting
                }
            }

            def isConnectionFull(): Boolean = {
                hzSoConf.maxConn match {
                    case 0 => false
                    case x if(hzSoConf.maxConn < x) => true
                    case _ => false
                }
            }

            def loopRunning() {
                log_trace("SocketServer:loopRunning")
                react {
//                    case dataReceived @ HZDataReceived(_) => {
//                        log_debug("SocketServer:loopRunning:HZDataReceived")
//                        parent ! dataReceived
//                    }
//                    case sendData @ HZDataSending(_) => {
//                        log_debug("SocketServer:loopRunning:HZDataSending")
//                        socketActor ! sendData
//                    }
                    case HZAccepted(so) => {
                        log_hzso_actor_debug("loopRunning:HZAccepted(%s)".format(so))
                        if(isConnectionFull()) {
                            log_hzso_actor_debug("loopRunning:HZAccepted:connectionFull")
                            so.close()
                        } else {
                            log_hzso_actor_trace("loopRunning:HZAccepted:connection is not Full")
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
                            val ioActor = startSocketIOActor(so, staticDataBuilder, self)(nextReceive)
                            actorSet += ioActor
                            val so_desc = HZSocketDescription(so)
                            ioActorMap += (ioActor -> so_desc)
                            parent ! HZIOStart(so_desc, ioActor, self)
                        }
                    }
                    case HZStop() => {
                        log_hzso_actor_debug("loopRunning:HZStop")
                        stopServer1(HZCommandStoped())
                    }
                    case HZStopWithReason(reason) => {
                        log_hzso_actor_debug("loopRunning:HZStopWithReason(%s)".format(reason))
                        stopServer1(HZCommandStopedWithReason(reason))
                    }
                    case Exit(stopedActor: Actor, reason) => {
                        if(stopedActor == acceptActor) {
                            log_hzso_actor_debug("loopRunning:1:Exit(%s,%s):AcceptActor Stoped".format(stopedActor,reason))
                            stopServer1(reason,stopedActor)     /* Stop Server ! */
                        } else {
                            reason match {
                                case _ :HZActorReason => {
                                    log_hzso_actor_debug("loopRunning:2:Exit(%s,%s)".format(stopedActor,reason))
                                    stopSocket1(reason,stopedActor)
                                }
                                case th: Throwable => {
                                    val unHandledExp = HZUnHandledException(th)
                                    log_hzso_actor_debug("loopRunning:3:Exit(%s,HZUnHandledException)".format(stopedActor),th)
                                    stopServer1(unHandledExp, stopedActor)
                                }
                                case _  => {
                                    val unknownReason = HZUnknownReason(reason)
                                    log_hzso_actor_debug("loopRunning:4:Exit(%s,%s)".format(stopedActor,unknownReason))
                                    stopServer1(unknownReason,stopedActor)     /* Stop Server ! */
                                }
                            }
                        }
                    }
                    case x => {
                        log_hzso_actor_debug("loopRunning:%s".format(x))
                    }
                }
            }

            def loopExiting() {
                log_trace("SocketServer:loopExiting")
                receive {
                    case Exit(stopedActor: Actor, reason) => {
                        reason match {
                            case _ :HZActorReason =>
                                log_hzso_actor_debug("loopExiting:1:Exit(%s,%s)".format(stopedActor,reason))
                            case th: Throwable =>
                                log_hzso_actor_debug("loopExiting:2:Exit(%s,HZUnHandledException)".format(stopedActor),th)
                            case _  =>
                                log_hzso_actor_debug("loopExiting:3:Exit(%s,%s)".format(stopedActor,HZUnknownReason(reason)))
                        }
                        actorSet -= stopedActor
                        if(actorSet.isEmpty) exit(originReason)
                    }
                    case x => log_hzso_actor_debug("loopExiting:%s".format(x))
                }
            }

            /*
             * main loop
             */
            loopfunc = loopRunning
            acceptActor = startAccepterActor(serverSocket, hzSoConf.acceptTimeout, self)
            actorSet += acceptActor
            loop {
                loopfunc()
            }
        }
    }
}

object HZSocketServer {
    implicit val logger = getLogger(this.getClass.getName)

    import org.hirosezouen.hznet.{HZSocketControler => hzso}

    def startSocketServer(hzSoConf: HZSoServerConf,
                          staticDataBuilder: SocketIOStaticDataBuilder,
                          parent: Actor,
                          linkParent: Boolean = true)
                         (nextBody: hzso.NextReceiver): Actor
    = {
        log_debug("startSocketServer(%s,%s,%s,%s)".format(hzSoConf,staticDataBuilder,parent,linkParent))
        HZSocketServer(hzSoConf).startSocketServerActor(staticDataBuilder, parent, linkParent)(nextBody)
    }
}

