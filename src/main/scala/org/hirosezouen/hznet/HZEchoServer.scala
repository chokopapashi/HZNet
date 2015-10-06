/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import java.io.File

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.Exception._

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Terminated
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy.Stop
import akka.actor.SupervisorStrategy.Escalate

import com.typesafe.config.ConfigFactory

import org.hirosezouen.hzactor.HZActor._
import org.hirosezouen.hzutil.HZIO._
import org.hirosezouen.hzutil.HZLog._

import HZSocketServer._

object HZEchoServer {
    implicit val logger = getLogger(this.getClass.getName)

    class MainActor(port: Int) extends Actor {
        log_trace("MainActor")

        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
            case _: Exception => Stop
            case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
        }

        private val actorStates = HZActorStates()

        override def preStart() {
            log_trace("preStart")

            actorStates += startSocketServer(HZSoServerConf(port),
                                             SocketIOStaticDataBuilder,
                                             "EchoServer")
            {
                case (_, HZIOStart(so_desc,_,_)) => {
                    log_info("Client connected:%s".format(so_desc))
                }
                case (_, HZDataReceived(receivedData)) => {
                    log_info(new String(receivedData))
                    self ! HZDataSending(receivedData)
                }
                case (_, HZIOStop(_,reason,_,_)) => {
                    log_info("Connection closed:%s".format(reason))
                }
            }

            val quit_r = "(?i)^q$".r
            actorStates += InputActor.start(System.in) {
                case quit_r() => {
                    System.in.close
                }
            }
        }
        override def postRestart(reason: Throwable): Unit = ()  /* Disable the call to preStart() after restarts. */

        def receive = {
            case Terminated(stopedActor: ActorRef) => {
                log_trace("MainActor:receive:Terminated(%s)".format(stopedActor))
                actorStates -= stopedActor
                if(actorStates.isEmpty) {
                    log_trace("MainActor:receive:Terminated:actorStates.isEmpty==true")
                    context.system.shutdown()
                } else {
                    log_trace("MainActor:receive:Terminated:actorStates.isEmpty==false")
                    actorStates.foreach(_.actor ! HZStop())
                    System.in.close()   /* InputAcotorはclose()の例外で停止する */
                    context.become(receiveExiting)
                }
            }
            case reason: HZActorReason => {
                log_debug("MainActor:receive:HZActorReason=%s".format(reason))
                actorStates.addReason(sender, reason)
            }
        }

        def receiveExiting: Actor.Receive = {
            case Terminated(stopedActor: ActorRef) => {
                log_trace("MainActor:receiveExiting:Terminated(%s)".format(stopedActor))
                actorStates -= stopedActor
                if(actorStates.isEmpty)
                    context.system.shutdown()
            }
            case reason: HZActorReason => {
                log_debug("MainActor:receiveExiting:HZActorReason=%s".format(reason))
                actorStates.addReason(sender, reason)
            }
        }
    }
    object MainActor {
        def start(port: Int)(implicit system: ActorRefFactory): ActorRef = {
            log_debug("MainActor:Start")
            system.actorOf(Props(new MainActor(port)))
        }
    }

    def main(args: Array[String]) {
        log_info("HZEchoServer:Start")

        val port = if(args.length < 1) {
            log_error("Error : Argument required.")
            sys.exit(1)
        } else {
            args(0).toInt
        }

        val config = ConfigFactory.parseFile(new File("../application.conf"))
        implicit val system = ActorSystem("HZEchoServer", config)
        MainActor.start(port)
        system.awaitTermination()

        log_info("HZEchoServer:end")
    }
}

