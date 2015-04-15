/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
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

import scala.actors._
import scala.actors.Actor._
import scala.util.control.Exception._

import org.hirosezouen.hzutil._
import HZActor._
import HZLog._

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
case class HZReqAddActor(actor: Actor) extends HZActorCommand
case class HZReqDelActor(actor: Actor) extends HZActorCommand

case class HZDataReceived(receivedData: Array[Byte]) extends HZActorInformation
case class HZAccepted(so: Socket) extends HZActorInformation
case class HZIOStart(so_desc: HZSocketDescription, ioActor: Actor, socketActor: Actor) extends HZActorInformation
case class HZIOStop(so_desc: HZSocketDescription, reason: AnyRef, stopedActor: Actor, ioActor: Actor, socketActor: Actor) extends HZActorInformation
case class HZSocketStop(so_desc: HZSocketDescription, reason: AnyRef, stopedActor: Actor, socketActor: Actor) extends HZActorInformation

case class HZEstablished(socket: Socket, actor: Actor) extends HZActorReason
case class HZConnectTimeout() extends HZActorReason
case class HZSocketDisabled() extends HZActorReason
case class HZPeerClosed() extends HZActorReason

case class HZSoClientConf(endPoint: InetSocketAddress,
                          localSocketAddressOpt: Option[InetSocketAddress],
                          connTimeout: Int,
                          recvTimeout: Int)
{
    lazy val hostName = endPoint.getHostName
    lazy val port = endPoint.getPort 
}
object HZSoClientConf {
    def apply(endPoint: InetSocketAddress): HZSoClientConf = new HZSoClientConf(endPoint,None,0,0)
    def apply(endPoint: InetSocketAddress, localSocketAddress: InetSocketAddress): HZSoClientConf =
        new HZSoClientConf(endPoint,Some(localSocketAddress),0,0)
    def apply(endPoint: InetSocketAddress, localSocketAddress: InetSocketAddress, connTimeout: Int, recvTimeout: Int): HZSoClientConf =
        new HZSoClientConf(endPoint,Some(localSocketAddress),connTimeout,recvTimeout)
    def apply(hostName: String ,port: Int): HZSoClientConf = new HZSoClientConf(new InetSocketAddress(hostName,port),None,0,0)
    def apply(hostName: String ,port: Int, connTimeout: Int, recvTimeout: Int): HZSoClientConf =
        new HZSoClientConf(new InetSocketAddress(hostName,port),None,connTimeout,recvTimeout)
    def apply(hostName: String ,port: Int, localName: String, localPort: Int, connTimeout: Int, recvTimeout: Int): HZSoClientConf =
        new HZSoClientConf(new InetSocketAddress(hostName,port),Some(new InetSocketAddress(localName,localPort)),connTimeout,recvTimeout)
}

case class HZSoServerConf(port: Int,
                          acceptTimeout: Int = 0,
                          recvTimeout: Int = 0,
                          maxConn: Int = 0)

trait SocketIOStaticDataImpl {
    private var _so_desc: HZSocketDescription = null
    def so_desc = _so_desc
    private def so_desc_=(sd: HZSocketDescription) = _so_desc = sd

    private var _ioActor: Actor = null
    def ioActor = _ioActor
    private def ioActor_=(a: Actor) = _ioActor = a

    private var _socketActor: Actor = null
    def socketActor = _socketActor
    private def socketActor_=(a: Actor) = _socketActor = a

    private [hznet] def apply(sd: HZSocketDescription, ia: Actor, sa: Actor): Unit = {
        so_desc = sd
        ioActor = ia
        socketActor = sa
    }

    def unapply(s: Any, ia: Any, sa: Any): Boolean = (s.isInstanceOf[Socket] && ia.isInstanceOf[Actor] && sa.isInstanceOf[Actor])
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

private object HZSocketControler {
    implicit val logger = getLogger(this.getClass.getName)

    case class ActorName(name: String, so_desc: HZSocketDescription = HZSocketDescription(null)) {
        override def toString: String = "[%s,%s,%s]".format(name,self,so_desc)
    }
    def log_hzso_actor_debug()(implicit actorName: ActorName) = log_debug(actorName.toString)
    def log_hzso_actor_debug(msg: => String)(implicit actorName: ActorName) = log_debug("%s:%s".format(actorName,msg))
    def log_hzso_actor_debug(msg: => String, th: Throwable)(implicit actorName: ActorName) = log_debug("%s:%s".format(actorName,msg),th)
    def log_hzso_actor_trace()(implicit actorName: ActorName) = log_trace(actorName.toString)
    def log_hzso_actor_trace(msg: => String)(implicit actorName: ActorName) = log_trace("%s:%s".format(actorName,msg))
    def log_hzso_actor_trace(msg: => String, th: Throwable)(implicit actorName: ActorName) = log_trace("%s:%s".format(actorName,msg),th)
    def log_hzso_actor_error(msg: => String = "")(implicit actorName: ActorName) = log_error("%s:%s".format(actorName,msg))

    def startSenderActor(outStream: BufferedOutputStream, so_desc: HZSocketDescription, parent: Actor): Actor = {
        log_debug("startSenderActor(%s,%s)".format(so_desc,parent))

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

        actor {
            implicit val actorName = ActorName("Sender",so_desc)
            log_hzso_actor_debug()
            link(parent)

            loop {
                react {
                    case HZStop() => {
                        log_hzso_actor_debug("HZStop")
                        exit(HZCommandStoped())
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
                                exit(HZErrorStoped(th))
                            }
                        }
                    }
                }
            }
        }
    }

    def startReceiverActor(inStream: BufferedInputStream, so_desc: HZSocketDescription, parent: Actor): Actor = {
        log_debug("startReceiverActor(%s,%s)".format(so_desc,parent))

        val readBuff = new Array[Byte](4096)
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

        actor {
            implicit val actorName = ActorName("Receiver",so_desc)
            log_hzso_actor_debug()
            link(parent)

            loop {
                /*
                 * ReceiverにはSocketを渡さいないので、isSocketReadable()は使えない。
                 * SocketがcloseしたかどうかはreceiveData()の例外で判断する。
                 */
                receiveData(inStream) match {
                    case Right(receivedDataOpt) => {
                        receivedDataOpt match {
                            case Some(receivedData) => {
                                log_hzso_actor_debug("receiveData:Right(%s)".format(receivedData))
                                log_hzso_actor_trace("receiveData:Right:%n%s".format(hexDump(receivedData)))
                                parent ! HZDataReceived(receivedData)
                            }
                            case None => exit(HZPeerClosed())
                        }
                    }
                    case Left(th) => th match {
                        case _: SocketTimeoutException => {
                            log_hzso_actor_trace("receiveData:Left(SocketTimeoutException)")
                        }
                        case _: SocketException => {
                            log_hzso_actor_error("receiveData:Left(SocektExcpetion(%s))".format(th.getMessage))
                            exit(HZErrorStoped(th))
                        }
                        case _: IOException => {
                            log_hzso_actor_error("receiveData:Left(IOExcpetion(%s))".format(th.getMessage))
                            exit(HZErrorStoped(th))
                        }
                        case _: InterruptedException => {
                            log_hzso_actor_debug("receiveData:Left(InterruptedException)")
                        }
                        case _ => {
                            log_hzso_actor_error("receiveData:Left(%s)".format(th))
                            log_hzso_actor_debug("receiveData:Left",th)
                            exit(HZErrorStoped(th))
                        }
                    }
                }
            }
        }
    }

    type NextReceiver = PartialFunction[Tuple2[SocketIOStaticData,Any],Any]

    def startSocketIOActor(socket: Socket, staticDataBuilder: SocketIOStaticDataBuilder, parent: Actor)
                          (nextReceiver: NextReceiver): Actor =
    {
        log_debug("startSocketIOActor(%s,%s,%s)".format(socket,staticDataBuilder,parent))

        actor {
            val so_desc = HZSocketDescription(socket)
            implicit val actorName = ActorName("SocketIO",so_desc)
            log_hzso_actor_debug()

            link(parent)
            self.trapExit = true

            def isSocketSendable(socket: Socket): Boolean = {
                log_hzso_actor_trace("isSocketSendable:isConnected=%s,isClosed=%s,isOutputShutdown=%s"
                          .format(socket.isConnected,socket.isClosed,socket.isOutputShutdown))
                socket.isConnected && (!socket.isClosed) && (!socket.isOutputShutdown)
            }

            def isSocketReadable(socket: Socket): Boolean = {
                log_hzso_actor_trace("isSocketReadable:isConnected=%s,isClosed=%s,isInputShutdown=%s"
                          .format(socket.isConnected,socket.isClosed,socket.isInputShutdown))
                socket.isConnected && (!socket.isClosed) && (!socket.isInputShutdown)
            }

            val staticData = staticDataBuilder.build()
            staticData(so_desc, self, parent)
            staticData.initialize

            val out = new BufferedOutputStream(socket.getOutputStream)
            val senderActor = startSenderActor(out, so_desc, self)

            val in = new BufferedInputStream(socket.getInputStream)
            val receiverActor = startReceiverActor(in, so_desc, self)

            var actorSet = Set.empty[Actor]
            actorSet += (receiverActor, senderActor)
            
            var originReason: AnyRef = null
            var loopfunc: () => Unit = null

            def stopIO1(reason: AnyRef, stopedActor: Actor = null) {
                (nextReceiver orElse ({
                    case x => log_hzso_actor_debug("loopRunning:stopIO1:nextReceiver:orElse:%s".format(x))
                }: NextReceiver))((staticData,HZIOStop(HZSocketDescription(socket),reason,stopedActor,self,parent)))
                staticData.cleanUp
                socket.close
                if(reason != null) originReason = reason
                if(stopedActor != null) actorSet -= stopedActor
                if(actorSet.isEmpty)
                    exit(originReason)
                else {
                    actorSet.foreach(_ ! HZStop())
                    loopfunc = loopExiting
                }
            }

            def loopRunning() {
                log_hzso_actor_trace("loopRunning")
                receive {
//                    case dataReceived @ HZDataReceived(r) => {
//                        log_debug("SocketIO:loopRunning:HZDataReceived(%s)".format(r))
//                        parent ! dataReceived
//                    }
                    case sendData @ HZDataSending(s) => {
                        log_hzso_actor_debug("loopRunning:HZDataSending(%s)".format(s))
                        if(isSocketSendable(socket)) {
                            log_hzso_actor_trace("loopRunning:HZDataSending:isSocketSendable:true")
                            senderActor ! sendData
                        } else {
                            log_hzso_actor_trace("loopRunning:HZDataSending:isSocketSendable:false")
                            stopIO1(HZSocketDisabled())
                        }
                    }
                    case HZReqAddActor(a) => {
                        log_hzso_actor_debug("loopRunning:HZReqAddActor(%s)".format(a))
                        actorSet += a
                    }
                    case HZReqDelActor(a) => {
                        log_hzso_actor_debug("loopRunning:HZReqDelActor(%s)".format(a))
                        actorSet -= a
                    }
                    case HZStop() => {
                        log_hzso_actor_debug("loopRunning:HZStop")
                        stopIO1(HZCommandStoped())
                    }
                    case HZStopWithReason(reason) => {
                        log_hzso_actor_debug("loopRunning:HZStopWithReason(%s)".format(reason))
                        stopIO1(HZCommandStopedWithReason(reason))
                    }
                    case Exit(stopedActor: Actor, reason) => {
                        reason match {
                            case _ :HZActorReason => {
                                log_hzso_actor_debug("loopRunning:1:Exit(%s,%s)".format(stopedActor,reason))
                                stopIO1(reason, stopedActor)
                            }
                            case th: Throwable => {
                                val unHandledExp = HZUnHandledException(th)
                                log_hzso_actor_debug("loopRunning:2:Exit(%s,HZUnHandledException)".format(stopedActor),th)
                                stopIO1(unHandledExp, stopedActor)
                            }
                            case _  => {
                                val unknownReason = HZUnknownReason(reason)
                                log_hzso_actor_debug("loopRunning:3:Exit(%s,%s)".format(stopedActor,unknownReason))
                                stopIO1(unknownReason, stopedActor)
                            }
                        }
                    }
                    case x => {
                        (nextReceiver orElse ({
                            case _ => log_hzso_actor_debug("loopRunning:nextReceiver:orElse:%s".format(x))
                        }: NextReceiver))((staticData,x))
                    }
                }
            }

            def loopExiting() {
                log_hzso_actor_trace("loopExiting")
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
            self ! HZIOStart(HZSocketDescription(socket),self,parent)
            loopfunc = loopRunning 
            loop {
                loopfunc()
            }
        }
    }

    def startConnectorActor(address: SocketAddress, localSocketAddressOpt: Option[InetSocketAddress], timeout: Int, parent: Actor): Actor = {
        log_debug("startConnectorActor(%s,%s,%d,%s)".format(address,localSocketAddressOpt,timeout,parent))

        actor {
            implicit val actorName = ActorName("Connector")
            log_hzso_actor_debug()
            link(parent)

            val socket = new Socket

            catching(classOf[IOException]) either {
                localSocketAddressOpt match {
                    case Some(socketAddress) => {
                        log_hzso_actor_debug("socket.bind(%s)".format(socketAddress))
                        socket.bind(socketAddress)
                    }
                    case None => /* Nothing to do */
                }
            } match {
                case Right(so) => so
                case Left(th) => th match {
                    case _: BindException => {
                        log_hzso_actor_error("socket.bind:Left(BindException(%s))".format(th.getMessage))
                        exit(HZErrorStoped(th))
                    }
                    case _: IOException => {
                        log_hzso_actor_error("socket.bind:Left(IOExcpetion(%s))".format(th.getMessage))
                        exit(HZErrorStoped(th))
                    }
                    case _ => {
                        log_hzso_actor_error("socket.bind:Left(%s)".format(th)) 
                        log_hzso_actor_debug("socket.bind:Left",th) 
                        exit(HZErrorStoped(th))
                    }
                }
            }

            catching(classOf[IOException]) either {
                socket.connect(address, timeout)
                socket
            } match {
                case Right(so) => {
                    log_hzso_actor_debug("socket.connect:Right(%s)".format(so))
                    exit(HZEstablished(so,self))
                }
                case Left(th) => th match {
                    case _: SocketTimeoutException => {
                        log_hzso_actor_error("socket.connect:Left(SocketTimeoutException(%s))".format(th.getMessage))
                        exit(HZConnectTimeout())
                    }
                    case _: ConnectException => {
                        log_hzso_actor_error("socket.connect:Left(ConnectException(%s))".format(th.getMessage))
                        exit(HZErrorStoped(th))
                    }
                    case _: SocketException => {
                        log_hzso_actor_error("socket.connect:Left(SocektExcpetion(%s))".format(th.getMessage))
                        exit(HZErrorStoped(th))
                    }
                    case _: IOException => {
                        log_hzso_actor_error("socket.connect:Left(IOExcpetion(%s))".format(th.getMessage))
                        exit(HZErrorStoped(th))
                    }
                    case _ => {
                        log_hzso_actor_error("socket.connect:Left(%s)".format(th)) 
                        log_hzso_actor_debug("socket.connect:Left",th) 
                        exit(HZErrorStoped(th))
                    }
                }
            }
        }
    }

    def startAccepterActor(serverSocket: ServerSocket, timeout: Int, parent: Actor): Actor = {
        log_debug("startAccepterActor(%s,%d,%s)".format(serverSocket,timeout,parent))

        actor {
            implicit val actorName = ActorName("Accepter")
            log_hzso_actor_debug()
            link(parent)

            timeout match {
                case 0 => /* Nothing to do. */
                case t => catching(classOf[IOException]) either {
                    serverSocket.setSoTimeout(t)
                } match {
                    case Right(_) => /* Ok, Nothing to do. */
                    case Left(th) => {
                        log_hzso_actor_error("serverSocket.setSoTimeout:Left(%s)".format(th)) 
                        log_hzso_actor_debug("serverSocket.setSoTimeout:Left",th) 
                        exit(HZErrorStoped(th))
                    }
                }
            }

            loop {
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
                            exit(HZConnectTimeout())
                        }
                        case _: SocketException => {
                            log_hzso_actor_error("serverSocket.accept:Left(SocektExcpetion(%s))".format(th.getMessage))
                            exit(HZErrorStoped(th))
                        }
                        case _: IOException => {
                            log_hzso_actor_error("serverSocket.accept:Left(IOExcpetion(%s))".format(th.getMessage))
                            exit(HZErrorStoped(th))
                        }
                        case _ => {
                            log_hzso_actor_error("serverSocket.accept:Left(%s)".format(th)) 
                            log_hzso_actor_debug("serverSocket.accept:Left",th) 
                            exit(HZErrorStoped(th))
                        }
                    }
                }
            }
        }
    }
}

