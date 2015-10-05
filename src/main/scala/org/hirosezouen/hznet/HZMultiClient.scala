/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

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
import akka.actor.ReceiveTimeout
import akka.actor.SupervisorStrategy.Stop
import akka.actor.SupervisorStrategy.Escalate

import com.typesafe.config.ConfigFactory

import org.hirosezouen.hzactor.HZActor._
import org.hirosezouen.hzutil.HZIO._
import org.hirosezouen.hzutil.HZLog._

import HZSocketClient._

object HZMultiClient {
    implicit val logger = getLogger(this.getClass.getName)

    private val ioActorQueue = new ConcurrentLinkedQueue[ActorRef]()

    class TimerActor(parent: ActorRef) extends Actor {
        log_trace(self.toString)

        context.setReceiveTimeout(1000 milliseconds)
        var num = 0
        def receive = {
            case ReceiveTimeout => {
                val ioActor = ioActorQueue.poll
                ioActor ! f"Message Number ($num%d)."
                ioActorQueue.offer(ioActor)
                num += 1
            }
            case HZStop() => exitNormaly(parent)

        }
    }
    object TimerActor {
        def start()(implicit parent: ActorRef, system: ActorRefFactory): ActorRef = {
            log_trace(s"TimerActor:start($parent)")
            system.actorOf(Props(new TimerActor(parent)), "TimerActor")
        }
    }

    class MainActor(ip: String, port: Int, maxClient: Int) extends Actor {
        log_trace(self.toString)

        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
            case _: Exception => Stop
            case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
        }

        private val actorStates = HZActorStates()

        override def preStart() {
            log_trace("preStart")

            val quit_r = "(?i)^q$".r
            actorStates += InputActor.start(System.in) {
                case quit_r() => System.in.close
                case s        => actorStates.foreach(_.actor ! HZDataSending(s.getBytes))
            }

            case class MultiClientSocketIOStaticData(num: Int) extends SocketIOStaticData {
                def initialize() {}
                def cleanUp() {}
            }
            case class MultiClientSocketIOStaticDataBuilder(num: Int) extends SocketIOStaticDataBuilder {
                def build(): SocketIOStaticData = MultiClientSocketIOStaticData(num)
            }

//            for(i <- 0 to (maxClient-1)) {
                val i = 0
                actorStates += startSocketClient(HZSoClientConf(ip,port,10000,0,false),
                                                 MultiClientSocketIOStaticDataBuilder(i),
                                                 self)
                {
                    case (staticData: MultiClientSocketIOStaticData, HZEstablished(_)) => {
                        ioActorQueue.offer(staticData.ioActor)
                    }
                    case (staticData: MultiClientSocketIOStaticData, s: String) => {
                        self ! HZDataSending(f"SocketClient(${self.toString})(${staticData.num}%d)($s)".getBytes)
                    }
                    case (_,HZDataReceived(receivedData)) => {
                        log_info(new String(receivedData))
                    }
                }
//            }

//            actorStates += TimerActor.start()
        }
        override def postRestart(reason: Throwable): Unit = ()  /* Disable the call to preStart() after restarts. */
        
        def receive = {
            case Terminated(stopedActor: ActorRef) => {
                log_trace(s"MainActor:receive:Terminated($stopedActor)")
                actorStates -= stopedActor
                if(actorStates.isEmpty) {
                    log_trace("MainActor:receive:Terminated:actorStates.isEmpty==true")
                    context.system.shutdown()
                } else {
                    log_trace("MainActor:receive:Terminated:actorStates.isEmpty==false")
                    actorStates.foreach(_.actor ! HZStop())
                    System.in.close()   /* stop InputAcotor by exception which throw as closing stream. */
                    context.become(receiveExiting)
                }
            }
            case reason: HZActorReason => {
                log_trace(s"MainActor:receive:HZActorReason=$reason")
                actorStates.addReason(sender, reason)
            }
        }

        def receiveExiting: Actor.Receive = {
            case Terminated(stopedActor: ActorRef) => {
                log_trace(s"MainActor:receiveExiting:Terminated($stopedActor)")
                actorStates -= stopedActor
                if(actorStates.isEmpty)
                    context.system.shutdown()
            }
            case reason: HZActorReason => {
                log_trace(s"MainActor:receiveExiting:HZActorReason=$reason")
                actorStates.addReason(sender, reason)
            }
        }

    }
    object MainActor {
        def start(ip: String, port: Int, maxClient: Int)(implicit system: ActorRefFactory): ActorRef = {
            log_trace("MainActor:Start")
            system.actorOf(Props(new MainActor(ip, port, maxClient)), "MainActor")
        }
    }

    def main(args: Array[String]) {
        log_info("HZMultiClient:Start")

//        sys.props("actors.corePoolSize") = "20"

        if(args.length < 3) {
            log_error("error : Argument required.")
            sys.exit(1)
        }
        val ip = args(0)
        val port = catching(classOf[NumberFormatException]) opt args(1).toInt match {
            case Some(p) => p
            case None => {
                log_error("error : Port number.")
                sys.exit(2)
            }
        }
        val maxClient = catching(classOf[NumberFormatException]) opt args(2).toInt match {
            case Some(n) => n
            case None => {
                log_error("error : Clinet number max.")
                sys.exit(3)
            }
        }

        val config = ConfigFactory.parseFile(new File("application.conf"))
        implicit val system = ActorSystem("HZEchoClient", config)
        MainActor.start(ip, port, maxClient)
        system.awaitTermination()

        log_info("HZMultiClient:End")
    }
}

