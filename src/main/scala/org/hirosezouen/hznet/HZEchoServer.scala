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

import org.hirosezouen.hzutil.HZActor._
import org.hirosezouen.hzutil.HZIO._
import org.hirosezouen.hzutil.HZLog._

import HZSocketServer._

object HZEchoServer {
    implicit val logger = getLogger(this.getClass.getName)

    def main(args: Array[String]) {
        log_info("HZEchoServer:Start")

        val port = if(args.length < 1) {
            System.err.println("Error : Argument required.")
            sys.exit(1)
        } else {
            args(0).toInt
        }

        var actors: Set[Actor] = Set.empty

        actors += startInputActor(System.in) {
            case "q" | "Q" => {
                exit(HZNormalStoped())
            }
        }

        actors += startSocketServer(HZSoServerConf(port),
                                    SocketIOStaticDataBuilder,
                                    self) {
            case (_, HZIOStart(so_desc,_,_)) => {
                log_info("Client connected:%s".format(so_desc))
            }
            case (_, HZDataReceived(receivedData)) => {
                printf("Echo back : %s%n", new String(receivedData))
                self ! HZDataSending(receivedData)
            }
            case (_, HZIOStop(_,reason,_,_,_)) => {
                log_info("Connection closed:%s".format(reason))
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

        log_info("HZEchoServer:end")
    }
}

