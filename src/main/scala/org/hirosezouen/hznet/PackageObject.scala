/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen

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
}

