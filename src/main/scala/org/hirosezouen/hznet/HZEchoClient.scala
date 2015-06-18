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

import HZSocketClient._

object HZEchoClient {
    implicit val logger = getLogger(this.getClass.getName)

    class MainActor(ip: String, port: Int) extends Actor {
        log_trace("MainActor")

        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
            case _: Exception => Stop
            case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
        }

        private var actorStateSet = Set.empty[HZActorState]

        private val soClient =
            startSocketClient(
                HZSoClientConf(ip,port,10000,0,false),
                SocketIOStaticDataBuilder,
                self)
        {
            case (_,s: String) => {
                self ! HZDataSending(s.getBytes)
            }
            case (_,HZDataReceived(receivedData)) => {
                log_info(new String(receivedData))
            }
        }
        context.watch(soClient)
        actorStateSet += HZActorState(soClient)

        val inputActor = InputActor.start(System.in) {
            case "q" | "Q" => {
                context.stop(self)
            }
            case s => {
                soClient ! HZDataSending(s.getBytes)
            }
        }
        context.watch(inputActor)
        actorStateSet += HZActorState(inputActor)

        def receive = {
            case Terminated(stopedActor: Actor) => {
                log_trace("MainActor:receive:Terminated(%s)".format(stopedActor))
                actorStateSet = actorStateSet.filterNot(_.actor == stopedActor)
                if(actorStateSet.isEmpty) {
                    log_trace("MainActor:receive:Terminated:actorStateSet.isEmpty==true")
                    context.system.shutdown()
                } else {
                    log_trace("MainActor:receive:Terminated:actorStateSet.isEmpty==false")
                    actorStateSet.foreach(_.actor ! HZStop())
                    System.in.close()   /* InputAcotorはclose()の例外で停止する */
                    context.become(receiveExiting)
                }
            }
            case reason: HZActorReason => {
                log_debug("MainActor:receive:HZActorReason=%s".format(reason))
                actorStateSet = actorStateSet.map(as => if(as.actor == sender) HZActorState(as.actor, reason) else as)
            }
        }

        def receiveExiting: Actor.Receive = {
            case Terminated(stopedActor: Actor) => {
                log_trace("MainActor:receiveExiting:Terminated(%s)".format(stopedActor))
                actorStateSet = actorStateSet.filterNot(_.actor == stopedActor)
                if(actorStateSet.isEmpty)
                    context.system.shutdown()
            }
            case reason: HZActorReason => {
                log_debug("MainActor:receiveExiting:HZActorReason=%s".format(reason))
                actorStateSet = actorStateSet.map(as => if(as.actor == sender) HZActorState(as.actor, reason) else as)
            }
        }
    }
    object MainActor {
        def start(ip: String, port: Int)(implicit system: ActorRefFactory): ActorRef = {
            log_debug("MainActor:Start")
            system.actorOf(Props(new MainActor(ip, port)))
        }
    }

    def main(args: Array[String]) {
        log_info("HZEchoClient:Start")

        if(args.length < 2) {
            log_error("error : Argument required.")
            sys.exit(0)
        }
        val ip = args(0)
        val port = catching(classOf[NumberFormatException]) opt args(1).toInt match {
            case Some(p) => p
            case None => {
                log_error("error : Port number.")
                sys.exit(1)
            }
        }

        val config = ConfigFactory.parseFile(new File("../application.conf"))
        implicit val system = ActorSystem("HZEchoClient", config)
        MainActor.start(ip, port)
        system.awaitTermination()

        log_info("HZEchoClient:end")
    }
}

