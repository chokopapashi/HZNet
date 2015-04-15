/*
 * Copyright (c) 2013, Hidekatsu Hirose
 * Copyright (c) 2013, Hirose-Zouen
 * This file is subject to the terms and conditions defined in
 * This file is subject to the terms and conditions defined in
 * file 'LICENSE.txt', which is part of this source code package.
 */

package org.hirosezouen.hznet

import java.security.MessageDigest

import org.hirosezouen.hzutil._

object HZHTTP_Digest_Authentication {

    case class HashHex(hash: IndexedSeq[Byte]) {
        import HashHex._
        require(hash.size == LENGTH)

        lazy val hexStr = hash.map("%02x".format(_)).mkString
        def getBytes: Array[Byte] = hexStr.getBytes 
        override def toString(): String = hexStr
    }
    object HashHex {
        def LENGTH = 16 
        def empty(): HashHex = HashHex(new Array[Byte](LENGTH))
    }

    /* calculate H(A1) as per spec */
    def DigestCalcHA1(pszAlg: String,
                      pszUserName: String,
                      pszRealm: String,
                      pszPassword: String,
                      pszNonce: String,
                      pszCNonce: String
                     ): HashHex = {
        var md = MessageDigest.getInstance("MD5")
        md.update(pszUserName.getBytes)
        md.update(":".getBytes)
        md.update(pszRealm.getBytes)
        md.update(":".getBytes)
        md.update(pszPassword.getBytes)
        var ha1 = md.clone().asInstanceOf[MessageDigest].digest

        if(pszAlg.compareToIgnoreCase("md5-sess") == 0) {
            md.update(":".getBytes)
            md.update(pszNonce.getBytes)
            md.update(":".getBytes)
            md.update(pszCNonce.getBytes)
            ha1 = md.digest
        }

        HashHex(ha1)
    }

    /* calculate request-digest/response-digest as per HTTP Digest spec */
    def DigestCalcResponse(HA1: HashHex,           /* H(A1) */
                           pszNonce: String,       /* nonce from server */
                           pszNonceCount: String,  /* 8 hex digits */
                           pszCNonce: String,      /* client nonce */
                           pszQop: String,         /* qop-value: "", "auth", "auth-int" */
                           pszMethod: String,      /* method from the request */
                           pszDigestUri: String,   /* requested URL */
                           HEntity: HashHex        /* H(entity body) if qop="auth-int" */
                          ): HashHex = {
        var md = MessageDigest.getInstance("MD5")
        md.update(pszMethod.getBytes)
        md.update(":".getBytes)
        md.update(pszDigestUri.getBytes)
        if(pszQop.compareToIgnoreCase("auth-int") == 0) {
            md.update(":".getBytes)
            md.update(HEntity.getBytes)
        }
        val HA2 = HashHex(md.digest)

        md = MessageDigest.getInstance("MD5")
        md.update(HA1.getBytes)
        md.update(":".getBytes)
        md.update(pszNonce.getBytes)
        md.update(":".getBytes)
        if(pszQop.length != 0) {
            md.update(pszNonceCount.getBytes)
            md.update(":".getBytes)
            md.update(pszCNonce.getBytes)
            md.update(":".getBytes)
            md.update(pszQop.getBytes)
            md.update(":".getBytes)
        }
        md.update(HA2.getBytes)
        HashHex(md.digest)
    }
}

