package com.itshenry.canteenclient.utils

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.util.Base64
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NfcHelper {

    companion object {
        private const val MIME_TYPE = "application/canteen"

        // NFC卡片数据结构
        data class NfcCardData(
            val qrData: String,
            val lastCollectedDate: String
        )

        /**
         * 从NFC标签读取数据
         */
        fun readFromTag(tag: Tag): NfcCardData? {
            val ndef = Ndef.get(tag) ?: return null

            try {
                ndef.connect()
                val ndefMessage = ndef.ndefMessage

                if (ndefMessage != null && ndefMessage.records.isNotEmpty()) {
                    val records = ndefMessage.records

                    // 期望至少有两个记录：二维码数据和最后领餐日期
                    if (records.size >= 2) {
                        val qrData = parsePayload(records[0])
                        val lastCollectedDate = parsePayload(records[1])

                        return NfcCardData(qrData, lastCollectedDate)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    ndef.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            return null
        }

        /**
         * 解析NDEF记录的payload
         */
        private fun parsePayload(record: NdefRecord): String {
            return try {
                when {
                    record.tnf == NdefRecord.TNF_MIME_MEDIA -> {
                        // MIME类型记录，直接读取payload
                        String(record.payload, Charsets.UTF_8)
                    }

                    record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                        // 文本记录，需要跳过语言代码
                        val payload = record.payload
                        val languageCodeLength = (payload[0].toInt() and 0x3F)
                        String(
                            payload,
                            languageCodeLength + 1,
                            payload.size - languageCodeLength - 1,
                            Charsets.UTF_8
                        )
                    }

                    else -> {
                        // 其他类型，直接转换
                        String(record.payload, Charsets.UTF_8)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }

        /**
         * 向NFC标签写入数据
         */
        fun writeToTag(tag: Tag, qrData: String, lastCollectedDate: String): Boolean {
            val ndef = Ndef.get(tag) ?: return false

            try {
                ndef.connect()

                if (!ndef.isWritable) {
                    return false
                }

                // 创建NDEF记录
                val qrRecord = NdefRecord.createMime(MIME_TYPE, qrData.toByteArray(Charsets.UTF_8))
                val dateRecord =
                    NdefRecord.createMime(MIME_TYPE, lastCollectedDate.toByteArray(Charsets.UTF_8))

                val message = NdefMessage(arrayOf(qrRecord, dateRecord))

                // 检查消息大小是否超过标签容量
                if (message.toByteArray().size > ndef.maxSize) {
                    return false
                }

                ndef.writeNdefMessage(message)
                return true

            } catch (e: Exception) {
                e.printStackTrace()
                return false
            } finally {
                try {
                    ndef.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        /**
         * 获取今天的日期字符串
         */
        fun getTodayDateString(): String {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateString = dateFormat.format(Date())
            val md = MessageDigest.getInstance("MD5")
            val hashBytes = md.digest(dateString.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(hashBytes, Base64.NO_WRAP)
        }

        /**
         * 检查日期是否是今天
         */
        fun isToday(dateString: String?): Boolean {
            return dateString == getTodayDateString()
        }

        /**
         * 检查离线模式下是否可以领餐（基于NFC卡片中的日期）
         */
        fun canCollectOffline(lastCollectedDate: String?): Boolean {
            return !isToday(lastCollectedDate)
        }
    }
}
