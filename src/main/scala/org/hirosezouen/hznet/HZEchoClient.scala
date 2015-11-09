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

import HZSocketClient._
//import HZSocketControler.{NextReceiver, SocketIOActor}

class MyInputActor(in: InputStream) extends InputActor(in, defaultInputFilter) {
    val quit_r = "(?i)^q$".r

    override val input: PFInput = {
        case quit_r() => System.in.close
        case s => {
//                soClient ! HZDataSending(s.getBytes)
            context.actorSelection("..") ! HZDataSending(s.getBytes)
        }
    }
}
object MyInputActor {
    def start(in: InputStream)(implicit context: ActorContext): ActorRef
        = context.actorOf(Props(new MyInputActor(in)), "MyInputActor")
}

class HZEchoClient(ip: String, port: Int, name: String) extends Actor {
    implicit val logger = getLogger(this.getClass.getName)
    log_trace(name)

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
        case _: Exception => Stop
        case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
    }

    private val actorStates = HZActorStates()

    override def preStart() {
        log_trace(s"$name:preStart")

        actorStates += startSocketClient(HZSoClientConf(ip,port,10000,0,false),
                                         SocketIOStaticDataBuilder,
                                         "EchoClientActor")
        {
            case (_,ioActorProxy,s: String) => {
                ioActorProxy.self ! HZDataSending(s.getBytes)
            }
            case (_,_,HZDataReceived(receivedData)) => {
                log_info(new String(receivedData))
            }
        }

        actorStates += MyInputActor.start(System.in)
    }
    override def postRestart(reason: Throwable): Unit = ()  /* Disable the call to preStart() after restarts. */

    def receive = {
        case Terminated(stopedActor: ActorRef) => {
            log_trace(s"$name:receive:Terminated(%s)".format(stopedActor))
            actorStates -= stopedActor
            if(actorStates.isEmpty) {
                log_trace(s"$name:receive:Terminated:actorStates.isEmpty==true")
                context.system.terminate()
            } else {
                log_trace(s"$name:receive:Terminated:actorStates.isEmpty==false")
                actorStates.foreach(_.actor ! HZStop())
                System.in.close()   /* InputAcotorはclose()の例外で停止する */
                context.become(receiveExiting)
            }
        }
        case reason: HZActorReason => {
            log_debug(s"$name:receive:HZActorReason=%s".format(reason))
            actorStates.addReason(sender, reason)
        }
    }

    def receiveExiting: Actor.Receive = {
        case Terminated(stopedActor: ActorRef) => {
            log_trace(s"$name:receiveExiting:Terminated(%s)".format(stopedActor))
            actorStates -= stopedActor
            if(actorStates.isEmpty)
                context.system.terminate()
        }
        case reason: HZActorReason => {
            log_debug(s"$name:receiveExiting:HZActorReason=%s".format(reason))
            actorStates.addReason(sender, reason)
        }
    }
}

object HZEchoClient {
    implicit val logger = getLogger(this.getClass.getName)

    def start(ip: String, port: Int)(implicit system: ActorRefFactory): ActorRef = {
        log_debug("HZEchoClient$:Start")
        system.actorOf(Props(new HZEchoClient(ip, port, "HZEchoClient")), "HZEchoClient")
    }

    def main(args: Array[String]) {
        log_info("HZEchoClient$:Start")

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
        implicit val system = ActorSystem("HZEchoClient$", config)
        start(ip, port)
        Await.result(system.whenTerminated, Duration.Inf)

        log_info("HZEchoClient$:end")
    }
}

