/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import java.io.InputStream
import java.io.File

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.Exception._

import akka.actor.Actor
import akka.actor.ActorContext
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
//import HZSocketControler.{NextReceiver, SocketIOActor}

class HZEchoServerInputActor(in: InputStream) extends InputActor(in, defaultInputFilter) {
    val quit_r = "(?i)^q$".r

    override val input: PFInput = {
        case quit_r() => System.in.close
        case s => {
//                soClient ! HZDataSending(s.getBytes)
            context.actorSelection("..") ! HZDataSending(s.getBytes)
        }
    }
}
object HZEchoServerInputActor {
    def start(in: InputStream)(implicit context: ActorContext): ActorRef
        = context.actorOf(Props(new HZEchoServerInputActor(in)), "HZEchoServerInputActor")
}

class HZEchoServer(port: Int, name: String) extends Actor {
    implicit val logger = getLogger(this.getClass.getName)
    log_trace(name)

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
        case _: Exception => Stop
        case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
    }

    private val actorStates = HZActorStates()

    override def preStart() {
        log_trace("preStart")

        actorStates += startSocketServer(HZSoServerConf(port),
                                         SocketIOStaticDataBuilder,
                                         "EchoServerActor")
        {
            case (_, _, HZIOStart(so_desc)) => {
                log_info("Client connected:%s".format(so_desc))
            }
            case (_, ioActorProxy, HZDataReceived(receivedData)) => {
                log_info(new String(receivedData))
                ioActorProxy.self ! HZDataSending(receivedData)
            }
            case (_, _, HZIOStop(_,reason)) => {
                log_info("Connection closed:%s".format(reason))
            }
        }

        actorStates += HZEchoServerInputActor.start(System.in)
    }
    override def postRestart(reason: Throwable): Unit = ()  /* Disable the call to preStart() after restarts. */

    def receive = {
        case Terminated(stopedActor: ActorRef) => {
            log_trace("MainActor:receive:Terminated(%s)".format(stopedActor))
            actorStates -= stopedActor
            if(actorStates.isEmpty) {
                log_trace("MainActor:receive:Terminated:actorStates.isEmpty==true")
                context.system.terminate()
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
                context.system.terminate()
        }
        case reason: HZActorReason => {
            log_debug("MainActor:receiveExiting:HZActorReason=%s".format(reason))
            actorStates.addReason(sender, reason)
        }
    }
}

object HZEchoServer {
    implicit val logger = getLogger(this.getClass.getName)

    def startHZEchoServerActor(port: Int)(implicit system: ActorRefFactory): ActorRef = {
        log_debug("HZEchoServer$:startHZEchoServerActor")
        system.actorOf(Props(new HZEchoServer(port,"HZEchoServer")), "HZEchoServer")
    }

    def start(args: Array[String]) {
        log_info("HZEchoServer$:Start")

        val port = if(args.length < 1) {
            log_error("a argument required.")
            sys.exit(1)
        } else 
            catching(classOf[NumberFormatException]) opt args(0).toInt match {
                case Some(p) => p
                case None => {
                    log_error("port number.")
                    sys.exit(1)
                }
            }

//        val config = ConfigFactory.parseFile(new File("application.conf"))
        val config = ConfigFactory.load("application.conf")
        implicit val system = ActorSystem("HZEchoServer", config)
        startHZEchoServerActor(port)
        Await.result(system.whenTerminated, Duration.Inf)

        log_info("HZEchoServer:end")
    }
}

