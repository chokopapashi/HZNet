package org.hirosezouen.hznet

import org.scalatest.FunSuite

import org.hirosezouen.hznet._

class PackageObjectTest extends FunSuite {

    test("macAddressString2Bytes") {
        assert(Array(0xAB,0xCD,0xEF,0x01,0x02,0x03).map(_.toByte) sameElements macAddressString2Bytes("AB:CD:EF:01:02:03"))
    }

    test("macAddressBytes2String") {
        assertResult("AB:CD:EF:01:02:03")(macAddressBytes2String(Array(0xAB,0xCD,0xEF,0x01,0x02,0x03).map(_.toByte)))
    }
}

