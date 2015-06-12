/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.BindException
import java.net.ConnectException 
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

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

import org.hirosezouen.hzutil._
import HZLog._
import org.hirosezouen.hzactor._
import HZActor._

case class HZSocketDescription(so: Socket) {
    val desc = if(so == null) "None"
               else so.toString
    val shortDesc = if(so == null) "None"
                    else f"[${so.getInetAddress}%s:${so.getPort}%d,${so.getLocalPort}%d]"
    val fullDesc = if(so == null) "None"
                   else f"[${so.getInetAddress}%s:${so.getPort}%d,${so.getLocalAddress}%s:${so.getLocalPort}%d]"
    override def toString(): String = desc
}

case class HZDataSending(sendingData: Array[Byte]) extends HZActorCommand
case class HZReqAddActor(actor: ActorRef) extends HZActorCommand
case class HZReqDelActor(actor: ActorRef) extends HZActorCommand

case class HZDataReceived(receivedData: Array[Byte]) extends HZActorInformation
case class HZAccepted(so: Socket) extends HZActorInformation
case class HZEstablished(socket: Socket) extends HZActorInformation
case class HZIOStart(so_desc: HZSocketDescription, ioActor: ActorRef, socketActor: ActorRef) extends HZActorInformation
case class HZIOStop(so_desc: HZSocketDescription, reason: AnyRef, ioActor: ActorRef, socketActor: ActorRef) extends HZActorInformation
case class HZSocketStop(so_desc: HZSocketDescription, reason: AnyRef, stopedActor: ActorRef, socketActor: ActorRef) extends HZActorInformation

case class HZConnectTimeout() extends HZActorReason
case class HZSocketDisabled() extends HZActorReason
case class HZPeerClosed() extends HZActorReason

case class HZSoClientConf(endPoint: InetSocketAddress,
                          localSocketAddressOpt: Option[InetSocketAddress],
                          connTimeout: Int,
                          recvTimeout: Int,
                          reuseAddress: Boolean)
{
    lazy val hostName = endPoint.getHostName
    lazy val port = endPoint.getPort 
}
object HZSoClientConf {
    def apply(endPoint: InetSocketAddress): HZSoClientConf = new HZSoClientConf(endPoint,None,0,0,false)
    def apply(endPoint: InetSocketAddress, localSocketAddress: InetSocketAddress): HZSoClientConf =
        new HZSoClientConf(endPoint,Some(localSocketAddress),0,0,false)
    def apply(endPoint: InetSocketAddress, localSocketAddress: InetSocketAddress, connTimeout: Int, recvTimeout: Int, reuseAddress: Boolean): HZSoClientConf =
        new HZSoClientConf(endPoint,Some(localSocketAddress),connTimeout,recvTimeout,reuseAddress)
    def apply(hostName: String ,port: Int): HZSoClientConf = new HZSoClientConf(new InetSocketAddress(hostName,port),None,0,0,false)
    def apply(hostName: String ,port: Int, connTimeout: Int, recvTimeout: Int, reuseAddress: Boolean): HZSoClientConf =
        new HZSoClientConf(new InetSocketAddress(hostName,port),None,connTimeout,recvTimeout,reuseAddress)
    def apply(hostName: String ,port: Int, localName: String, localPort: Int, connTimeout: Int, recvTimeout: Int, reuseAddress: Boolean): HZSoClientConf =
        new HZSoClientConf(new InetSocketAddress(hostName,port),Some(new InetSocketAddress(localName,localPort)),connTimeout,recvTimeout,reuseAddress)
}

case class HZSoServerConf(port: Int,
                          acceptTimeout: Int = 0,
                          recvTimeout: Int = 0,
                          maxConn: Int = 0)

trait SocketIOStaticDataImpl {
    private var _so_desc: HZSocketDescription = null
    def so_desc = _so_desc
    private def so_desc_=(sd: HZSocketDescription) = _so_desc = sd

    private var _ioActor: ActorRef = null
    def ioActor = _ioActor
    private def ioActor_=(a: ActorRef) = _ioActor = a

    private var _socketActor: ActorRef = null
    def socketActor = _socketActor
    private def socketActor_=(a: ActorRef) = _socketActor = a

    private [hznet] def apply(sd: HZSocketDescription, ia: ActorRef, sa: ActorRef): Unit = {
        so_desc = sd
        ioActor = ia
        socketActor = sa
    }

    def unapply(s: Any, ia: Any, sa: Any): Boolean = (s.isInstanceOf[Socket] && ia.isInstanceOf[ActorRef] && sa.isInstanceOf[ActorRef])
}

trait SocketIOStaticData extends SocketIOStaticDataImpl {
    def initialize()
    def cleanUp()
}

trait SocketIOStaticDataBuilder {
    def build(): SocketIOStaticData
}

object SocketIOStaticDataBuilder extends SocketIOStaticDataBuilder {
    def build() = new SocketIOStaticData {
        def initialize() {}
        def cleanUp() {}
    }
}

case class ReceiveLoop()

/* ======================================================================== */

object HZSocketControler {
    implicit val logger = getLogger(this.getClass.getName)

    case class ActorName(name: String, actor: ActorRef, so_desc: HZSocketDescription = HZSocketDescription(null)) {
        override def toString: String = "[%s,%s,%s]".format(name,actor,so_desc)
    }
    def log_hzso_actor_debug()(implicit actorName: ActorName) = log_debug(actorName.toString)
    def log_hzso_actor_debug(msg: => String)(implicit actorName: ActorName) = log_debug("%s:%s".format(actorName,msg))
    def log_hzso_actor_debug(msg: => String, th: Throwable)(implicit actorName: ActorName) = log_debug("%s:%s".format(actorName,msg),th)
    def log_hzso_actor_trace()(implicit actorName: ActorName) = log_trace(actorName.toString)
    def log_hzso_actor_trace(msg: => String)(implicit actorName: ActorName) = log_trace("%s:%s".format(actorName,msg))
    def log_hzso_actor_trace(msg: => String, th: Throwable)(implicit actorName: ActorName) = log_trace("%s:%s".format(actorName,msg),th)
    def log_hzso_actor_error(msg: => String = "")(implicit actorName: ActorName) = log_error("%s:%s".format(actorName,msg))

    /* ---------------------------------------------------------------------*/

    class SenderActor(outStream: BufferedOutputStream, so_desc: HZSocketDescription, parent: ActorRef) extends Actor {
        implicit val actorName = ActorName("Sender", self, so_desc)

        override def preStart() {
            log_hzso_actor_debug()
        }

        def sendData(sendingData: Array[Byte], out: BufferedOutputStream)(implicit actorName: ActorName): Option[Throwable] = {
            log_hzso_actor_trace("%s:sendData(%s,%s)".format(so_desc,sendingData,out))

            val ret = catching(classOf[IOException]) either {
                out.write(sendingData)
                out.flush
            } match {
                case Right(_) => {
                    log_hzso_actor_trace("%s:sendData:%d=out.write(%s)".format(so_desc,sendingData.length,sendingData)) 
                    None
                }
                case Left(th) => {
                    log_hzso_actor_trace("%s:sendData:out.write:%s".format(so_desc,th))
                    Some(th)
                }
            }
            ret
        }

        def receive = {
            case HZStop() => {
                log_hzso_actor_debug("HZStop")
                exitNormaly(HZCommandStoped(), parent)
            }
            case HZDataSending(sendingData) => {
                log_hzso_actor_debug("HZDataSending(%s)=%d".format(sendingData,sendingData.length))
                log_hzso_actor_trace("HZDataSending:%n%s".format(hexDump(sendingData)))
                sendData(sendingData, outStream) match {
                    case None => {
                        log_hzso_actor_trace("HZDataSending:sendData:None")
                    }
                    case Some(th) => {
                        log_hzso_actor_error("HZDataSending:sendData(%s,%s):%s".format(sendingData,outStream,th))
                        log_hzso_actor_debug("HZDataSending:sendData:%n%s".format(self,sendingData,outStream),th)
                        exitWithError(th, parent)
                    }
                }
            }
        }
    }
    object SenderActor {
        def start(outStream: BufferedOutputStream, so_desc: HZSocketDescription, parent: ActorRef)
                 (implicit system: ActorRefFactory): ActorRef
        = {
            log_trace("SenderActor:start(%s,%s)".format(so_desc,parent))
            system.actorOf(Props(new SenderActor(outStream,so_desc,parent)), "Sender")
        }
    }

    /* ---------------------------------------------------------------------*/

    class ReceiverActor(inStream: BufferedInputStream, so_desc: HZSocketDescription, parent: ActorRef) extends Actor {
        implicit val actorName = ActorName("Receiver", self, so_desc)

        override def preStart() {
            log_hzso_actor_debug()
            self ! ReceiveLoop()
        }

        private val readBuff = new Array[Byte](4096)
        def receiveData(in: BufferedInputStream)(implicit actorName: ActorName): Either[Throwable,Option[Array[Byte]]] = {
            log_hzso_actor_trace("receiveData(%s)".format(in))

            val ret = catching(classOf[IOException]) either {
                in.read(readBuff)
            } match {
                case Right(c) => {
                    log_hzso_actor_trace("receiveData:%d=in.read(%s)".format(c,readBuff)) 
                    if(c < 0) {
                        log_hzso_actor_debug("receiveData:in.read:%d".format(c)) 
                        Right(None)
                    } else if(c == 0) {
                        log_hzso_actor_debug("receiveData:in.read:0") 
                        val th = new IllegalArgumentException("0=in.read()")
                        log_hzso_actor_trace("receiveData:in.read",th) 
                        Left(th)
                    } else
                        Right(Some(readBuff.take(c)))
                }
                case Left(th) => {
                    log_hzso_actor_debug("receiveData:in.read:%s".format(th)) 
                    Left(th)
                }
            }
            ret
        }

        def receive = {
            case ReceiveLoop() => {
                /*
                 * Can not use function "isSocketReadable()" at this place.
                 * Therefore, it determines whether socket close or not.
                 */
                receiveData(inStream) match {
                    case Right(receivedDataOpt) => {
                        receivedDataOpt match {
                            case Some(receivedData) => {
                                log_hzso_actor_debug("receiveData:Right(%s)".format(receivedData))
                                log_hzso_actor_trace("receiveData:Right:%n%s".format(hexDump(receivedData)))
                                parent ! HZDataReceived(receivedData)
                            }
                            case None => {
                                exitNormaly(HZPeerClosed(),parent)
                            }
                        }
                    }
                    case Left(th) => th match {
                        case _: SocketTimeoutException => {
                            log_hzso_actor_trace("receiveData:Left(SocketTimeoutException)")
                        }
                        case _: InterruptedException => {
                            log_hzso_actor_debug("receiveData:Left(InterruptedException)")
                        }
                        case _: SocketException => {
                            log_hzso_actor_error("receiveData:Left(SocektExcpetion(%s))".format(th.getMessage))
                            exitWithError(th, parent)
                        }
                        case _: IOException => {
                            log_hzso_actor_error("receiveData:Left(IOExcpetion(%s))".format(th.getMessage))
                            exitWithError(th, parent)
                        }
                        case _ => {
                            log_hzso_actor_error("receiveData:Left(%s)".format(th))
                            log_hzso_actor_debug("receiveData:Left",th)
                            exitWithError(th, parent)
                        }
                    }
                }
            }
        }
    }
    object ReceiverActor {
        def start(inStream: BufferedInputStream, so_desc: HZSocketDescription, parent: ActorRef)
                 (implicit system: ActorRefFactory): ActorRef
        = {
            log_debug("ReceiverActor:start(%s,%s)".format(so_desc,parent))
            system.actorOf(Props(new ReceiverActor(inStream,so_desc,parent)), "Receiver")
        }
    }

    /* ---------------------------------------------------------------------*/

    case class HZActorState(actor: ActorRef, stopReason: HZActorReason = HZNullReason)

    type NextReceiver = PartialFunction[Tuple2[SocketIOStaticData,Any],Any]

    class SocketIOActor(socket: Socket, staticDataBuilder: SocketIOStaticDataBuilder, parent: ActorRef,
                        nextReceiver: NextReceiver) extends Actor
    {
        private val so_desc = HZSocketDescription(socket)
        implicit val actorName = ActorName("SocketIO", self, so_desc)

        override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=1, withinTimeRange=1 minutes, loggingEnabled=true) {
            case _: Exception => Stop
            case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
        }

        def isSocketSendable(so: Socket): Boolean = {
            log_hzso_actor_trace("isSocketSendable:isConnected=%s,isClosed=%s,isOutputShutdown=%s"
                      .format(so.isConnected,so.isClosed,so.isOutputShutdown))
            so.isConnected && (!so.isClosed) && (!so.isOutputShutdown)
        }

        def isSocketReadable(so: Socket): Boolean = {
            log_hzso_actor_trace("isSocketReadable:isConnected=%s,isClosed=%s,isInputShutdown=%s"
                      .format(so.isConnected,so.isClosed,so.isInputShutdown))
            so.isConnected && (!so.isClosed) && (!so.isInputShutdown)
        }

        private val staticData = staticDataBuilder.build()
        staticData(so_desc, self, parent)
        staticData.initialize

        private val out = new BufferedOutputStream(socket.getOutputStream)
        private val senderActor = SenderActor.start(out, so_desc, self)
        context.watch(senderActor)

        private val in = new BufferedInputStream(socket.getInputStream)
        private val receiverActor = ReceiverActor.start(in, so_desc, self)
        context.watch(receiverActor)

        private var actorStateSet = Set.empty[HZActorState]
        actorStateSet += (HZActorState(receiverActor), HZActorState(senderActor))

        override def preStart() {
            log_hzso_actor_debug()
        }
        
        var originReason: HZActorReason = null
        def stopIO1(reason: HZActorReason, stopedActorOpt: Option[ActorRef] = None) {
            (nextReceiver orElse ({
                case x => log_hzso_actor_debug("loopRunning:stopIO1:nextReceiver:orElse:%s".format(x))
            }: NextReceiver))((staticData,HZIOStop(HZSocketDescription(socket),reason,self,parent)))
            staticData.cleanUp
            socket.close
            if(reason != null) originReason = reason
            stopedActorOpt match {
                case Some(a) => actorStateSet = actorStateSet.filterNot(_.actor == a)
                case None =>
            }
            if(actorStateSet.isEmpty)
                exitNormaly(originReason,parent)
            else {
                actorStateSet.foreach(_.actor ! HZStop())
                context.become(receiveExiting)
            }
        }

        def receive() = {
//            case dataReceived @ HZDataReceived(r) => {
//                log_debug("SocketIO:receive:HZDataReceived(%s)".format(r))
//                parent ! dataReceived
//            }
            case sendData @ HZDataSending(s) => {
                log_hzso_actor_debug("receive:HZDataSending(%s)".format(s))
                if(isSocketSendable(socket)) {
                    log_hzso_actor_trace("receive:HZDataSending:isSocketSendable:true")
                    senderActor ! sendData
                } else {
                    log_hzso_actor_trace("receive:HZDataSending:isSocketSendable:false")
                    stopIO1(HZSocketDisabled())
                }
            }
            case HZReqAddActor(a) => {
                log_hzso_actor_debug("receive:HZReqAddActor(%s)".format(a))
                actorStateSet += HZActorState(a)
                context.watch(a)
            }
            case HZReqDelActor(a) => {
                log_hzso_actor_debug("receive:HZReqDelActor(%s)".format(a))
                actorStateSet = actorStateSet.filterNot(_.actor == a)
                context.unwatch(a)
            }
            case HZStop() => {
                log_hzso_actor_debug("receive:HZStop")
                stopIO1(HZCommandStoped())
            }
            case HZStopWithReason(reason) => {
                log_hzso_actor_debug("receive:HZStopWithReason(%s)".format(reason))
                stopIO1(HZCommandStopedWithReason(reason))
            }
            case Terminated(stopedActor) => {
                log_hzso_actor_debug("receive:Terminated(%s)".format(stopedActor))
                stopIO1(HZNullReason, Some(stopedActor))
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug("receive:HZActorReason=%s".format(reason))
                actorStateSet = actorStateSet.map(as => if(as.actor == sender) HZActorState(as.actor, reason) else as)
            }
            case x => {
                (nextReceiver orElse ({
                    case _ => log_hzso_actor_debug("receive:nextReceiver:orElse:%s".format(x))
                }: NextReceiver))((staticData,x))
            }
        }

        def receiveExiting: Actor.Receive = {
            case HZReqDelActor(a) => {
                log_hzso_actor_debug("receiveExiting:HZReqDelActor(%s)".format(a))
                actorStateSet = actorStateSet.filterNot(_.actor == a)
                context.unwatch(a)
            }
            case reason: HZActorReason => {
                log_hzso_actor_debug("receiveExiting:HZActorReason=%s".format(reason))
                actorStateSet = actorStateSet.map(as => if(as.actor == sender) HZActorState(as.actor, reason) else as)
            }
            case Terminated(stopedActor) => {
                log_hzso_actor_debug("receiveExiting:Terminated(%s)".format(stopedActor))
                actorStateSet = actorStateSet.filterNot(_.actor == stopedActor)
                log_hzso_actor_trace("receiveExiting:actorStateSet=%s".format(actorStateSet))
                if(actorStateSet.isEmpty)
                    exitNormaly(originReason,parent)
            }
            case x => log_hzso_actor_debug("receiveExiting:%s".format(x))
        }
    }
    object SocketIOActor {
        def start(socket: Socket, staticDataBuilder: SocketIOStaticDataBuilder, parent: ActorRef)
                 (nextReceiver: NextReceiver)
                 (implicit system: ActorRefFactory): ActorRef
        = {
            log_debug("SocketIOActor:start(%s,%s,%s)".format(socket,staticDataBuilder,parent))
            system.actorOf(Props(new SocketIOActor(socket, staticDataBuilder, parent, nextReceiver)), "SocketIO")
        }
    }

    /* ---------------------------------------------------------------------*/

    class ConnectorActor(address: SocketAddress, localSocketAddressOpt: Option[InetSocketAddress],
                         timeout: Int, reuseAddress: Boolean, parent: ActorRef) extends Actor
    {
        implicit val actorName = ActorName("Connector", self)

        private val socket = new Socket

        catching(classOf[IOException]) either {
            localSocketAddressOpt match {
                case Some(socketAddress) => {
                    log_hzso_actor_debug("socket.bind(%s)".format(socketAddress))
                    socket.setReuseAddress(reuseAddress)
                    socket.bind(socketAddress)
                }
                case None => /* Nothing to do */
            }
        } match {
            case Right(so) => so
            case Left(th) => th match {
                case _: BindException => {
                    log_hzso_actor_error("socket.bind:Left(BindException(%s))".format(th.getMessage))
                    exitWithError(th, parent)
                }
                case _: IOException => {
                    log_hzso_actor_error("socket.bind:Left(IOExcpetion(%s))".format(th.getMessage))
                    exitWithError(th, parent)
                }
                case _ => {
                    log_hzso_actor_error("socket.bind:Left(%s)".format(th)) 
                    log_hzso_actor_debug("socket.bind:Left",th) 
                    exitWithError(th, parent)
                }
            }
        }

        override def preStart() {
            log_hzso_actor_debug()
            self ! ReceiveLoop()
        }

        def receive = {
            case ReceiveLoop() => {
                catching(classOf[IOException]) either {
                    socket.connect(address, timeout)
                    socket
                } match {
                    case Right(so) => {
                        log_hzso_actor_debug("socket.connect:Right(%s)".format(so))
                        parent ! HZEstablished(so)
                        exitNormaly(parent)
                    }
                    case Left(th) => th match {
                        case _: SocketTimeoutException => {
                            log_hzso_actor_error("socket.connect:Left(SocketTimeoutException(%s))".format(th.getMessage))
                            exitNormaly(HZConnectTimeout(),parent)
                        }
                        case _: ConnectException => {
                            log_hzso_actor_error("socket.connect:Left(ConnectException(%s))".format(th.getMessage))
                            exitWithError(th, parent)
                        }
                        case _: SocketException => {
                            log_hzso_actor_error("socket.connect:Left(SocektExcpetion(%s))".format(th.getMessage))
                            exitWithError(th, parent)
                        }
                        case _: IOException => {
                            log_hzso_actor_error("socket.connect:Left(IOExcpetion(%s))".format(th.getMessage))
                            exitWithError(th, parent)
                        }
                        case _ => {
                            log_hzso_actor_error("socket.connect:Left(%s)".format(th)) 
                            log_hzso_actor_debug("socket.connect:Left",th) 
                            exitWithError(th, parent)
                        }
                    }
                }
            }
        }
    }
    object ConnectorActor {
        def start(address: SocketAddress, localSocketAddressOpt: Option[InetSocketAddress],
                  timeout: Int, reuseAddress: Boolean, parent: ActorRef)
                 (implicit system: ActorRefFactory): ActorRef
        = {
            log_debug("ConnectorActor:start(%s,%s,%d,%s,%s)".format(address,localSocketAddressOpt,timeout,reuseAddress,parent))
            system.actorOf(Props(new ConnectorActor(address,localSocketAddressOpt,timeout,reuseAddress,parent)), "Connector")
        }
    }

    class AccepterActor(serverSocket: ServerSocket, timeout: Int, parent: ActorRef) extends Actor {
        implicit val actorName = ActorName("Accepter", self)

        timeout match {
            case 0 => /* Nothing to do. */
            case t => catching(classOf[IOException]) either {
                serverSocket.setSoTimeout(t)
            } match {
                case Right(_) => /* Ok, Nothing to do. */
                case Left(th) => {
                    log_hzso_actor_error("serverSocket.setSoTimeout:Left(%s)".format(th)) 
                    log_hzso_actor_debug("serverSocket.setSoTimeout:Left",th) 
                    exitWithError(th, parent)
                }
            }
        }

        override def preStart() {
            log_hzso_actor_debug()
            self ! ReceiveLoop()
        }

        def receive = {
            case ReceiveLoop() => {
                catching(classOf[IOException]) either {
                    serverSocket.accept()
                } match {
                    case Right(so) => {
                        log_hzso_actor_debug("serverSocket.accept:Right(%s)".format(so))
                        parent ! HZAccepted(so)
                    }
                    case Left(th) => th match {
                        case _: SocketTimeoutException => {
                            log_hzso_actor_error("serverSocket.accept:Left(SocketTimeoutException(%s))".format(th.getMessage))
                            exitNormaly(HZConnectTimeout(),parent)
                        }
                        case _: SocketException => {
                            log_hzso_actor_error("serverSocket.accept:Left(SocektExcpetion(%s))".format(th.getMessage))
                            exitWithError(th, parent)
                        }
                        case _: IOException => {
                            log_hzso_actor_error("serverSocket.accept:Left(IOExcpetion(%s))".format(th.getMessage))
                            exitWithError(th, parent)
                        }
                        case _ => {
                            log_hzso_actor_error("serverSocket.accept:Left(%s)".format(th)) 
                            log_hzso_actor_debug("serverSocket.accept:Left",th) 
                            exitWithError(th, parent)
                        }
                    }
                }
            }
            self ! ReceiveLoop()
        }
    }
    object AccepterActor {
        def start(serverSocket: ServerSocket, timeout: Int, parent: ActorRef)
                 (implicit system: ActorRefFactory): ActorRef
        = {
            log_debug("AccepterActor.start(%s,%d,%s)".format(serverSocket,timeout,parent))
            system.actorOf(Props(new AccepterActor(serverSocket,timeout,parent)), "Accepter")
        }
    }
}

