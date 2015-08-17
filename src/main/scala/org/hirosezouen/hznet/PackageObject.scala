/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen

import java.nio.ByteBuffer
import java.net.InetAddress
import java.net.InetSocketAddress

package object hznet {
    def macAddressString2Bytes(macString: String): Array[Byte] = {
        val macStrArray = macString.split(":")
        assert(macStrArray.length == 6, f"macStringToBytes:macStrArray.length:expect=6,actual=${macStrArray.length}%d")
        macStrArray.map(java.lang.Integer.parseInt(_,16).toByte) 
    }
    def macAddressBytes2String(macBytes: Array[Byte]): String  = {
        assert(macBytes.length == 6, f"macAddressBytes2String:macBytes.length:expect=6,actual=${macBytes.length}%d")
        macBytes.map(b => f"$b%02X").mkString(":")
    }

    def getNetworkInterfaces: List[NetworkInterface] = NetworkInterface.getNetworkInterfaces().toList
    def getNetworkInterfaceByname(name: String): Option[NetworkInterface] = {
        val networkinterface = NetworkInterface.getByName(name)
        if(networkinterface == null) None
        else Some(networkinterface)
    }
    def getNetworkInterfaceAddresses(name: String): List[InetAddress] = getNetworkInterfaceByname(name) match {
        case Some(networkinterface) => networkinterface.getInetAddresses().toList
        case None => List.empty[InetAddress]
    }

    implicit class InetAddressString2Int(s: String) {
        def toInetAddressInt(): Int = InetAddress.getByName(s).toInetAddressInt
    }
    implicit class InetAddress2Int(inetAddress: InetAddress) {
        def toInetAddressInt(): Int = ByteBuffer.wrap(inetAddress.getAddress()).getInt
    }
    implicit class Int2InetAddress(i: Int) {
        def toInetAddress(buff: Array[Byte] = new Array(4)): InetAddress = InetAddress.getByAddress(ByteBuffer.wrap(buff).putInt(i).array)
        def toInetSocketAddress(buff: Array[Byte] = new Array(4)): InetSocketAddress = new InetSocketAddress(i.toInetAddress(buff),0)
        def mkInetAddressString(): String = f"(${i.toInetAddress()},$i%08X)"
    }
    implicit class IntSet2InetAddressStrs(intSet: Set[Int]) {
        def toInetAddressString(): String = intSet.toList.map(_.mkInetAddressString).mkString(",")
    }
    implicit class IntSeq2InetAddressStrs(intSeq: Seq[Int]) {
        def toInetAddressString(): String = intSeq.map(_.mkInetAddressString).mkString(",")
    }
}

