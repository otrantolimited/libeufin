/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.sandbox

import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream

/**
 * Helpers for dealing with order compression, encryption, decryption, chunking and re-assembly.
 */
class EbicsOrderUtil private constructor() {
    companion object {
        inline fun <reified T> decodeOrderDataXml(encodedOrderData: ByteArray): T {
            return InflaterInputStream(encodedOrderData.inputStream()).use {
                val bytes = it.readAllBytes()
                XMLUtil.convertStringToJaxb<T>(bytes.toString(Charsets.UTF_8)).value
            }
        }

        inline fun <reified T>encodeOrderDataXml(obj: T): ByteArray {
            val bytes = XMLUtil.convertJaxbToString(obj).toByteArray()
            return DeflaterInputStream(bytes.inputStream()).use {
                it.readAllBytes()
            }
        }
    }
}
