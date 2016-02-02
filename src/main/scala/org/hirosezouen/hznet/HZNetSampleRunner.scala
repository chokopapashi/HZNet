/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import org.hirosezouen.hzutil._
import HZLog._

object HZNetSampleRunner extends App {
    implicit val logger = getLogger(this.getClass.getName)

    def usage = """
           |usage : HZSampleRunner <param>
           |param : 1 or HZEchoClient params...
           |        2 or HZEchoServer params...""".stripMargin
    def printErrorAndUsage(msg: String) = {log_error(msg) ; log_info(usage)}

    if(args.nonEmpty) {
        args(0) match {
            case "1" | "HZEchoClient" => HZEchoClient.start(args.tail)
            case "2" | "HZEchoServer" => HZEchoServer.start(args.tail)
            case _ => printErrorAndUsage("error : wrong argument.")
        }
    } else
        printErrorAndUsage("argument required.")
}

