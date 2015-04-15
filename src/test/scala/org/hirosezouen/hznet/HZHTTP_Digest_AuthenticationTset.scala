package org.hirosezouen.hznet

import org.scalatest.FunSuite

class HTTP_Digest_AuthenticationTset extends FunSuite {
  
    test("HTTP_Digest_AuthenticationTset.01") {

        import HZHTTP_Digest_Authentication._

        val pszNonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093"
        val pszCNonce = "0a4f113b"
        val pszUser   = "Mufasa"
        val pszRealm = "testrealm@host.com"
        val pszPass = "Circle Of Life"
        val pszAlg = "md5"
        val szNonceCount = "00000001"
        val pszMethod = "GET"
        val pszQop = "auth"
        val pszURI = "/dir/index.html"

        val HA2 = HashHex.empty
        val HA1 = DigestCalcHA1(pszAlg, pszUser, pszRealm, pszPass, pszNonce, pszCNonce)
        val Response = DigestCalcResponse(HA1, pszNonce, szNonceCount, pszCNonce, pszQop, pszMethod, pszURI, HA2)
//        printf("HTTP_Digest_AuthenticationTset.01:Response = %s%n", Response)
        assertResult("6629fae49393a05397450978507c4ef1")(Response.toString)
    }
/*
    test("HTTP_Digest_AuthenticationTset.02") {

        import HZHTTP_Digest_Authentication._

//Authorization: Digest username="admin", realm="iF system", nonce="01:e7c8f4ed2917d934b284cad485cba0b1", nc=00000001, uri="/../16tansyuku_01.html?1", response="d68a6487ceec501826f0799b76d88a41", algorithm="MD5", cnonce="CFBAGMNBIMKONPJDEGGLDANKKGOOPMLDEHFBKCEM", qop="auth"


        val pszNonce = "01:e7c8f4ed2917d934b284cad485cba0b1"
        val pszCNonce = "CFBAGMNBIMKONPJDEGGLDANKKGOOPMLDEHFBKCEM"
        val pszUser   = "admin"
        val pszRealm = "iF system"
        val pszPass = "0000"
        val pszAlg = "md5"
        val szNonceCount = "00000001"
        val pszMethod = "GET"
        val pszQop = "auth"
        val pszURI = "/../16tansyuku_01.html?1"

        val HA2 = HashHex.empty
        val HA1 = DigestCalcHA1(pszAlg, pszUser, pszRealm, pszPass, pszNonce, pszCNonce)
        val Response = DigestCalcResponse(HA1, pszNonce, szNonceCount, pszCNonce, pszQop, pszMethod, pszURI, HA2)
//        printf("HTTP_Digest_AuthenticationTset.02:Response = %s%n", Response)
        assertResult("d68a6487ceec501826f0799b76d88a41")(Response.toString)
    }
*/
}

