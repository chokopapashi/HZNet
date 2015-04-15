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

object HZEchoBindClient {
    implicit val logger = getLogger(this.getClass.getName)

    def main(args: Array[String]) {
        log_info("HZEchoBindClient:Start")

        if(args.length < 4) {
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

        val localIp = args(2)
        val localPort = catching(classOf[NumberFormatException]) opt args(3).toInt match {
            case Some(p) => p
            case None => {
                log_error("error : Local port number.")
                sys.exit(2)
            }
        }

        var actors: Set[Actor] = Set.empty

        val soClient = startSocketClient(HZSoClientConf(ip,port,localIp,localPort,10000,0),
                                         SocketIOStaticDataBuilder,
                                         self) {
            case (_,s: String) => {
                self ! HZDataSending(s.getBytes)
            }
            case (_,HZDataReceived(receivedData)) => {
                println(new String(receivedData))
            }
        }
        actors += soClient

        actors += startInputActor(System.in) {
            case "q" | "Q" => {
                exit(HZNormalStoped())
            }
            case s => {
                soClient ! HZDataSending(s.getBytes)
            }
        }

        self.trapExit = true
        var loopFlag = true
        var mf: () => Unit = null
        
        def mainFun1() = receive {
            case Exit(stopedActor: Actor, reason) => {
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
            case Exit(stopedActor: Actor, reason) => {
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

        log_info("HZEchoBindClient:end")
    }
}

