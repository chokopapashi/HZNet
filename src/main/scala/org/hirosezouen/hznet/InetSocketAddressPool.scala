/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import java.net.InetSocketAddress
import scala.collection.immutable.Queue

import org.hirosezouen.hzutil._
import HZLog._

class InetSocketAddressPool(addrRangeStrOpt: Option[String], interfaceNameOpt: Option[String], exceptIPAddressesOpt: Option[String]) {
    implicit val logger = getLogger(this.getClass.getName)

    log_trace(f"InetSocketAddressPool($addrRangeStrOpt%s)")
   
    private val ip_regex = """\d{1,3}(?:\.\d{1,3}){3}"""
    private val address_r = s"""($ip_regex)""".r
    private val addressRange_r = s"""($ip_regex)-($ip_regex)""".r

    private val addrRange: List[Int] = addrRangeStrOpt match {
        case Some(addrRangeStr) => addrRangeStr.split(',').flatMap{
            ars => ars.trim match {
                case addressRange_r(start,end) => start.toInetAddressInt to end.toInetAddressInt
                case address_r(ip) => List(ip.toInetAddressInt)
                case x => throw new IllegalArgumentException("InetSocketAddressPool:invalid local_address_range format:$x")
            }
        }.toList
        case None => Nil
    }
    log_trace(f"InetSocketAddressPool:addrRange=${addrRange.toInetAddressString}%s,size=${addrRange.size}%d")

    private val interfaceAddresses: List[Int] = interfaceNameOpt match {
        case Some(interfaceName) => getNetworkInterfaceAddresses(interfaceName).map(_.toInetAddressInt).sorted
        case _ => Nil
    }
    log_trace(f"InetSocketAddressPool:interfaceAddresses:${interfaceAddresses.toInetAddressString}%s,size=${interfaceAddresses.size}%d")

    private var exceptIPAddresses: List[Int] = exceptIPAddressesOpt match {
        case Some(exceptIPAddress) => exceptIPAddress.split(',').flatMap{
            address => address.trim match {
                case addressRange_r(start,end) => (start.toInetAddressInt to end.toInetAddressInt).toList
                case address_r(ip) => List(ip.toInetAddressInt)
                case x => throw new IllegalArgumentException("InetSocketAddressPool:invalid except IP Address format:$x")
            }
        }.toList
        case None => Nil
    }
    log_trace(f"InetSocketAddressPool:exceptIPAddresses:${exceptIPAddresses.toInetAddressString}%s,size=${exceptIPAddresses.size}%d")

    var queue: Queue[InetSocketAddress] = if(addrRange.isEmpty && interfaceAddresses.isEmpty)
                    throw new IllegalArgumentException("InetSocketAddressPool:IPAddress dose not exist.")
                else if(addrRange.isEmpty)
                    Queue(((interfaceAddresses diff exceptIPAddresses).map(_.toInetSocketAddress())): _*)
                else if(interfaceAddresses.isEmpty)
                    Queue(((addrRange diff exceptIPAddresses).map(_.toInetSocketAddress())): _*)
                else
                    Queue((((addrRange intersect interfaceAddresses) diff exceptIPAddresses).map(_.toInetSocketAddress())): _*)
    log_debug(f"InetSocketAddressPool:queue.size==${queue.size}%d")

    def get(): Option[InetSocketAddress] = {
        log_trace(s"InetSocketAddressPool:get")
        if(0 < queue.size) {
            val (socketAddress, q) = queue.dequeue
            queue = q
            log_trace(f"InetSocketAddressPool:get:queue.size=${queue.size}%d:$socketAddress%s")
            Some(socketAddress)
        } else {
            log_trace(f"InetSocketAddressPool:get:NG:queue.size=${queue.size}%d")
            None
        }
    }

    def release(socketAddress: InetSocketAddress) {
        log_trace(s"InetSocketAddressPool:release($socketAddress)")
        queue = queue.enqueue(socketAddress)
        log_trace(f"InetSocketAddressPool:queue.size=${queue.size}%d")
    }

    override def toString() = "[" + queue.map(ip => s"$ip").mkString(",") + "]"
}

