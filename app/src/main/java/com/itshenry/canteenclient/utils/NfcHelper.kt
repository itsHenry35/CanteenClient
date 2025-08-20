package com.itshenry.canteenclient.utils

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NfcHelper {

    companion object {
        private const val TAG = "NfcHelper"

        // NFC卡片数据结构
        data class NfcCardData(
            val qrData: String,
            val lastCollectedDate: String
        )

        // MIFARE Classic 1K 结构:
        // - 16个扇区 (0-15)
        // - 每扇区4个块
        // - 每个扇区的最后一个块是扇区尾块(不能用于数据存储)

        // 数据布局设计（完全避开扇区0和扇区尾块）:
        // 扇区0通常被保护或使用非默认密钥，所以从扇区1开始
        // qrData (约48字节，需要3个块):
        //   扇区1: 块4,5,6 (块7是扇区尾块)
        // lastCollectedDate (约24字节，需要2个块):
        //   扇区2: 块8,9 (块10预留，块11是扇区尾块)
        //   扇区3: 全预留

        private val QR_BLOCKS = intArrayOf(4, 5, 6)  // 3个块，48字节
        private val DATE_BLOCKS = intArrayOf(8, 9)           // 2个块，32字节

        private val DEFAULT_KEY = ByteArray(6) { 0xFF.toByte() } // MIFARE默认密钥

        /**
         * 计算块所属的扇区
         */
        private fun blockToSector(block: Int): Int {
            return block / 4
        }

        /**
         * 对扇区进行认证（带缓存，避免重复认证）
         */
        private fun authenticateSector(
            mc: MifareClassic,
            sector: Int,
            lastAuthSector: Int?
        ): Boolean {
            // 如果已经认证过这个扇区，不需要重复认证
            if (sector == lastAuthSector) {
                return true
            }

            return try {
                val result = mc.authenticateSectorWithKeyA(sector, DEFAULT_KEY)
                if (!result) {
                    Log.e(TAG, "Failed to authenticate sector $sector with default key")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Authentication error for sector $sector: ${e.message}")
                false
            }
        }

        /** 读取卡片数据 */
        fun readFromTag(tag: Tag): NfcCardData? {
            val mc = MifareClassic.get(tag)
            if (mc == null) {
                Log.e(TAG, "Not a MIFARE Classic tag")
                return null
            }

            try {
                mc.connect()
                Log.d(TAG, "Connected to tag. Type: ${mc.type}, Size: ${mc.size} bytes")

                var lastAuthSector: Int? = null

                // 读取 qrData
                val qrBytes = ByteArray(QR_BLOCKS.size * 16)
                for ((index, block) in QR_BLOCKS.withIndex()) {
                    val sector = blockToSector(block)

                    if (!authenticateSector(mc, sector, lastAuthSector)) {
                        return null
                    }
                    lastAuthSector = sector

                    try {
                        val blockData = mc.readBlock(block)
                        System.arraycopy(blockData, 0, qrBytes, index * 16, 16)
                        Log.d(TAG, "Read block $block successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read block $block: ${e.message}")
                        return null
                    }
                }

                // 从字节数组中提取实际的字符串（去除末尾的0）
                val qrEndIndex = qrBytes.indexOfFirst { it == 0.toByte() }
                val qrData = if (qrEndIndex > 0) {
                    String(qrBytes, 0, qrEndIndex, Charsets.UTF_8)
                } else {
                    String(qrBytes, Charsets.UTF_8).trim()
                }

                // 读取 lastCollectedDate
                val dateBytes = ByteArray(DATE_BLOCKS.size * 16)
                for ((index, block) in DATE_BLOCKS.withIndex()) {
                    val sector = blockToSector(block)

                    if (!authenticateSector(mc, sector, lastAuthSector)) {
                        return null
                    }
                    lastAuthSector = sector

                    try {
                        val blockData = mc.readBlock(block)
                        System.arraycopy(blockData, 0, dateBytes, index * 16, 16)
                        Log.d(TAG, "Read block $block successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read block $block: ${e.message}")
                        return null
                    }
                }

                // 从字节数组中提取实际的字符串（去除末尾的0）
                val dateEndIndex = dateBytes.indexOfFirst { it == 0.toByte() }
                val lastCollectedDate = if (dateEndIndex > 0) {
                    String(dateBytes, 0, dateEndIndex, Charsets.UTF_8)
                } else {
                    String(dateBytes, Charsets.UTF_8).trim()
                }

                Log.d(TAG, "Read complete - QR: $qrData, Date: $lastCollectedDate")
                return NfcCardData(qrData, lastCollectedDate)

            } catch (e: Exception) {
                Log.e(TAG, "Read error: ${e.message}")
                e.printStackTrace()
            } finally {
                try {
                    mc.close()
                    Log.d(TAG, "Tag connection closed")
                } catch (_: IOException) {
                }
            }
            return null
        }

        /**
         * 向NFC标签写入数据
         */
        fun writeToTag(tag: Tag, lastCollectedDate: String): Boolean {
            val mc = MifareClassic.get(tag)
            if (mc == null) {
                Log.e(TAG, "Not a MIFARE Classic tag")
                return false
            }

            // 验证数据大小
            val dateBytes = lastCollectedDate.toByteArray(Charsets.UTF_8)

            if (dateBytes.size > DATE_BLOCKS.size * 16) {
                Log.e(
                    TAG,
                    "Date data too large: ${dateBytes.size} bytes, max: ${DATE_BLOCKS.size * 16}"
                )
                return false
            }

            try {
                mc.connect()
                Log.d(TAG, "Connected to tag. Type: ${mc.type}, Size: ${mc.size} bytes")
                Log.d(TAG, "Writing Date: $lastCollectedDate (${dateBytes.size} bytes)")

                var lastAuthSector: Int? = null

                // 准备 lastCollectedDate 数据（用0填充到块边界）
                val dateFull = ByteArray(DATE_BLOCKS.size * 16) { 0 }
                System.arraycopy(dateBytes, 0, dateFull, 0, dateBytes.size)

                // 写入 lastCollectedDate
                for ((index, block) in DATE_BLOCKS.withIndex()) {
                    val sector = blockToSector(block)

                    if (!authenticateSector(mc, sector, lastAuthSector)) {
                        return false
                    }
                    lastAuthSector = sector

                    val blockBytes = ByteArray(16)
                    System.arraycopy(dateFull, index * 16, blockBytes, 0, 16)

                    try {
                        mc.writeBlock(block, blockBytes)
                        Log.d(TAG, "Wrote to block $block (sector $sector)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write to block $block: ${e.message}")
                        return false
                    }
                }

                Log.d(TAG, "Write complete!")
                return true

            } catch (e: Exception) {
                Log.e(TAG, "Write error: ${e.message}")
                e.printStackTrace()
            } finally {
                try {
                    mc.close()
                    Log.d(TAG, "Tag connection closed")
                } catch (_: IOException) {
                }
            }
            return false
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