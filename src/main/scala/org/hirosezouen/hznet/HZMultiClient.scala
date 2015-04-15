/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import scala.actors._
import scala.actors.Actor._
import scala.util.control.Exception._

import org.hirosezouen.hzutil.HZActor._
import org.hirosezouen.hzutil.HZIO._
import org.hirosezouen.hzutil.HZLog._

import HZSocketClient._

object HZMultiClient {
    implicit val logger = getLogger(this.getClass.getName)

    sys.props("actors.corePoolSize") = "20"

    def main(args: Array[String]) {
        log_info("HZMultiClient:Start")

        sys.props("actors.corePoolSize") = "20"

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

        var actors = Set.empty[Actor]
        var clientArray = new Array[Actor](maxClient)

        case class SayMessage(msg: String)

        case class MultiClientSocketIOStaticData(num: Int) extends SocketIOStaticData {
            def initialize() {}
            def cleanUp() {}
        }

        case class MultiClientSocketIOStaticDataBuilder(num: Int) extends SocketIOStaticDataBuilder {
            def build(): SocketIOStaticData = {
                MultiClientSocketIOStaticData(num)
            }
        }

        val parent = self
        actors += actor {
            link(parent)
            var count = 0
            loop {
                reactWithin(200) {
                    case TIMEOUT => {
                        if(clientArray(count) != null) {
                            clientArray(count) ! "This is SocketClient %d".format(count)
                        }
                        count += 1
                        if(maxClient <= count)
                            count = 0
                    }
                    case HZStop() => exit(HZNormalStoped())
                }
            }
        }

        for(i <- 0 to (maxClient - 1)) {
            val soClient = startSocketClient(HZSoClientConf(ip,port,10000,0),
                                             MultiClientSocketIOStaticDataBuilder(i),
                                             self) {
                case (staticData: MultiClientSocketIOStaticData, HZEstablished(_,_)) => {
                    clientArray(staticData.num) = staticData.ioActor
                }
                case (_,s: String) => {
                    self ! HZDataSending(s.getBytes)
                }
                case (_,HZDataReceived(receivedData)) => {
                    println(new String(receivedData))
                }
            }
            actors += soClient

//            Thread.sleep(5000)
        }

        actors += startInputActor(System.in) {
            case "q" | "Q" => {
                exit(HZNormalStoped())
            }
        }

        self.trapExit = true
        var loopFlag = true
        var mf: () => Unit = null
        
        def mainFun1() = receive {
            case Exit(stopedActor: Actor, reason: HZActorReason) => {
                log_debug("main:mainFun1:Exit(%s,%s)".format(stopedActor,reason))
                actors -= stopedActor
                if(actors.isEmpty) {
                    loopFlag = false
                } else {
                    actors.foreach(_ ! HZStop())
                    System.in.close()   /* InputAcotorはclose()の例外で停止する */
                    mf = mainFun2
                }
            }
        }

        def mainFun2() = receive {
            case Exit(stopedActor: Actor, reason: HZActorReason) => {
                log_debug("main:mainFun2:Exit(%s,%s)".format(stopedActor,reason))
                actors -= stopedActor
                if(actors.isEmpty)
                    loopFlag = false
            }
        }

        /*
         * メイン処理
         */
        mf = mainFun1
        while(loopFlag) {
            mf()
        }

        log_info("HZMultiClient:end")
    }
}

