package com.foxfw.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Minimal Flipper-Zero RPC protobuf (PB_Main) codec — just the GUI screen-stream +
 * input-event messages we need, hand-rolled so the app carries no protobuf runtime.
 *
 * PB_Main (flipper.proto):   command_id = 1, command_status = 2, has_next = 3
 *   oneof content:  gui_start_screen_stream_request = 20
 *                   gui_stop_screen_stream_request  = 21
 *                   gui_screen_frame                = 22   (PB_Gui.ScreenFrame)
 *                   gui_send_input_event_request    = 23   (PB_Gui.SendInputEventRequest)
 * PB_Gui.ScreenFrame:            data = 1 (bytes), orientation = 2
 * PB_Gui.SendInputEventRequest:  key = 1, type = 2
 *
 * Messages travel over the serial characteristic length-delimited: a varint byte
 * count precedes each PB_Main (nanopb PB_ENCODE_DELIMITED).
 */
object Proto {

    // InputKey (gui.proto)
    const val KEY_UP = 0; const val KEY_DOWN = 1; const val KEY_RIGHT = 2
    const val KEY_LEFT = 3; const val KEY_OK = 4; const val KEY_BACK = 5
    // InputType (gui.proto)
    const val TYPE_PRESS = 0; const val TYPE_RELEASE = 1; const val TYPE_SHORT = 2
    const val TYPE_LONG = 3; const val TYPE_REPEAT = 4

    private fun varint(out: ArrayList<Byte>, value: Long) {
        var x = value
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x != 0L) out.add((b or 0x80).toByte()) else { out.add(b.toByte()); break }
        }
    }

    private fun tag(out: ArrayList<Byte>, field: Int, wire: Int) = varint(out, ((field.toLong()) shl 3) or wire.toLong())

    /** Wrap a built PB_Main body with its leading varint length (the on-wire delimiter). */
    private fun delimited(body: ArrayList<Byte>): ByteArray {
        val out = ArrayList<Byte>(body.size + 3)
        varint(out, body.size.toLong())
        out.addAll(body)
        return out.toByteArray()
    }

    // atomic: ids are allocated from both the UI thread (sendInput) and coroutine threads (allocId)
    private val cmdId = java.util.concurrent.atomic.AtomicInteger(1)
    private fun nextId() = cmdId.getAndIncrement()

    /** PB_Main{ command_id, gui_start_screen_stream_request{} } */
    fun startScreenStream(): ByteArray {
        val m = ArrayList<Byte>()
        tag(m, 1, 0); varint(m, nextId().toLong())          // command_id
        tag(m, 20, 2); varint(m, 0)                          // empty sub-message, length 0
        return delimited(m)
    }

    /** PB_Main{ command_id, gui_stop_screen_stream_request{} } */
    fun stopScreenStream(): ByteArray {
        val m = ArrayList<Byte>()
        tag(m, 1, 0); varint(m, nextId().toLong())
        tag(m, 21, 2); varint(m, 0)
        return delimited(m)
    }

    /** PB_Main{ command_id, gui_send_input_event_request{ key, type } } */
    fun sendInput(key: Int, type: Int): ByteArray {
        val sub = ArrayList<Byte>()
        tag(sub, 1, 0); varint(sub, key.toLong())
        tag(sub, 2, 0); varint(sub, type.toLong())
        val m = ArrayList<Byte>()
        tag(m, 1, 0); varint(m, nextId().toLong())
        tag(m, 23, 2); varint(m, sub.size.toLong()); m.addAll(sub)
        return delimited(m)
    }

    // ---- Storage RPC (ported from the FoxFW-ANDROID ProtobufCodec) ----
    // PB_Main storage oneof: read=9 read_resp=10 write=11 delete=12 mkdir=13.
    // StorageWriteRequest{ path=1, file=2{ data=4 } }.  File read_response field 10{ file=1{ data=4 } }.
    fun allocId(): Int = nextId()

    private fun lenField(out: ArrayList<Byte>, field: Int, data: ByteArray) {
        tag(out, field, 2); varint(out, data.size.toLong()); for (x in data) out.add(x)
    }
    private fun strField(out: ArrayList<Byte>, field: Int, s: String) = lenField(out, field, s.toByteArray(Charsets.UTF_8))

    private fun mainMsg(cmdId: Int, hasNext: Boolean, contentField: Int, content: ByteArray): ByteArray {
        val m = ArrayList<Byte>(content.size + 16)
        tag(m, 1, 0); varint(m, cmdId.toLong())                 // command_id
        if (hasNext) { tag(m, 3, 0); varint(m, 1) }             // has_next
        lenField(m, contentField, content)                      // oneof content
        return delimited(m)
    }

    fun writeRequest(cmdId: Int, path: String, data: ByteArray, hasNext: Boolean): ByteArray {
        val file = ArrayList<Byte>(data.size + 8); lenField(file, 4, data)   // File{ data=4 }
        val inner = ArrayList<Byte>(); strField(inner, 1, path); lenField(inner, 2, file.toByteArray())
        return mainMsg(cmdId, hasNext, 11, inner.toByteArray())
    }
    fun readRequest(cmdId: Int, path: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, path)
        return mainMsg(cmdId, false, 9, inner.toByteArray())
    }
    /** PB_Main{ storage_list_request(7){ path=1 } } — list a directory on the Flipper SD. */
    fun listRequest(cmdId: Int, path: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, path)
        return mainMsg(cmdId, false, 7, inner.toByteArray())
    }
    fun mkdirRequest(cmdId: Int, path: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, path)
        return mainMsg(cmdId, false, 13, inner.toByteArray())
    }
    fun deleteRequest(cmdId: Int, path: String, recursive: Boolean): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, path); if (recursive) { tag(inner, 2, 0); varint(inner, 1) }
        return mainMsg(cmdId, false, 12, inner.toByteArray())
    }

    /** PB_Main{ storage_rename_request(30){ old_path=1, new_path=2 } }. */
    fun renameRequest(cmdId: Int, oldPath: String, newPath: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, oldPath); strField(inner, 2, newPath)
        return mainMsg(cmdId, false, 30, inner.toByteArray())
    }

    /** PB_Main{ storage_backup_create_request(42){ archive_path=1 } } — tar /int to archive_path on SD. */
    fun backupCreateRequest(cmdId: Int, archivePath: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, archivePath)
        return mainMsg(cmdId, false, 42, inner.toByteArray())
    }
    /** PB_Main{ storage_backup_restore_request(43){ archive_path=1 } } — restore /int from an SD tar. */
    fun backupRestoreRequest(cmdId: Int, archivePath: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, archivePath)
        return mainMsg(cmdId, false, 43, inner.toByteArray())
    }

    /** PB_Main{ system_update_request(41){ update_manifest=1 } } — arm an SD-staged update. */
    fun updateRequest(cmdId: Int, manifest: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, manifest)
        return mainMsg(cmdId, false, 41, inner.toByteArray())
    }
    /** UpdateResponse(46){ code=1 } — UpdatePrepareResult (0 = OK, else a specific error). */
    fun parseUpdateCode(bytes: ByteArray): Int {
        val r = Reader(bytes, 0, bytes.size)
        while (!r.eof()) {
            val t = r.varint(); val f = (t ushr 3).toInt(); val w = (t and 7).toInt()
            if (f == 1 && w == 0) return r.varint().toInt() else skip(r, w)
        }
        return 0
    }

    /** PB_Main{ app_start_request(16){ name=1, args=2 } } — launch a built-in app or a .fap by path. */
    fun appStart(cmdId: Int, name: String, args: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, name); strField(inner, 2, args)
        return mainMsg(cmdId, false, 16, inner.toByteArray())
    }

    /** One entry from a storage_list_response (a file or a directory on the Flipper SD). */
    data class FileEntry(val name: String, val isDir: Boolean, val size: Long)

    class RpcResp(
        val cmdId: Int, val status: Int, val hasNext: Boolean, val readData: ByteArray?,
        val contentField: Int = 0, val contentBytes: ByteArray? = null,
        val files: List<FileEntry>? = null,
    )

    /** Parse a PB_Main response: command_id(1), command_status(2), has_next(3), read_response(10){file{data(4)}}. */
    fun parseResponse(msg: ByteArray): RpcResp {
        val r = Reader(msg, 0, msg.size)
        var cmdId = 0; var status = 0; var hasNext = false; var readData: ByteArray? = null
        var contentField = 0; var contentBytes: ByteArray? = null
        var files: MutableList<FileEntry>? = null
        while (!r.eof()) {
            val t = r.varint(); val field = (t ushr 3).toInt(); val wire = (t and 7).toInt()
            when {
                field == 1 && wire == 0 -> cmdId = r.varint().toInt()
                field == 2 && wire == 0 -> status = r.varint().toInt()
                field == 3 && wire == 0 -> hasNext = r.varint() != 0L
                field == 8 && wire == 2 -> {              // storage_list_response{ repeated file=1{ type=1, name=2, size=3 } }
                    val len = r.varint().toInt(); val sub = Reader(msg, r.p, minOf(r.p + len, msg.size)); r.p = sub.end
                    val list = files ?: ArrayList<FileEntry>().also { files = it }
                    while (!sub.eof()) {
                        val st = sub.varint(); val sf = (st ushr 3).toInt(); val sw = (st and 7).toInt()
                        if (sf == 1 && sw == 2) {          // one File message
                            val fl = sub.varint().toInt(); val f2 = Reader(msg, sub.p, minOf(sub.p + fl, msg.size)); sub.p = f2.end
                            var type = 0; var name = ""; var size = 0L
                            while (!f2.eof()) {
                                val ft = f2.varint(); val ff = (ft ushr 3).toInt(); val fw = (ft and 7).toInt()
                                when {
                                    ff == 1 && fw == 0 -> type = f2.varint().toInt()
                                    ff == 2 && fw == 2 -> { val nl = f2.varint().toInt(); val from = f2.p; val to = minOf(from + nl, msg.size); name = String(msg, from, to - from, Charsets.UTF_8); f2.p = to }
                                    ff == 3 && fw == 0 -> size = f2.varint()
                                    else -> skip(f2, fw)
                                }
                            }
                            if (name.isNotEmpty()) list.add(FileEntry(name, type == 1, size))
                        } else skip(sub, sw)
                    }
                }
                // system oneof content (device_info=33, power_info=45, get_datetime=36, protobuf_ver=40)
                wire == 2 && (field == 33 || field == 45 || field == 36 || field == 40 || field == 46) -> {
                    val len = r.varint().toInt(); val from = r.p; val to = minOf(from + len, msg.size); r.p = to
                    contentField = field; contentBytes = msg.copyOfRange(from, to)
                }
                field == 10 && wire == 2 -> {              // storage_read_response{ file=1{ data=4 } }
                    val len = r.varint().toInt(); val sub = Reader(msg, r.p, minOf(r.p + len, msg.size)); r.p = sub.end
                    while (!sub.eof()) {
                        val st = sub.varint(); val sf = (st ushr 3).toInt(); val sw = (st and 7).toInt()
                        if (sf == 1 && sw == 2) {           // file message
                            val fl = sub.varint().toInt(); val f2 = Reader(msg, sub.p, minOf(sub.p + fl, msg.size)); sub.p = f2.end
                            while (!f2.eof()) {
                                val ft = f2.varint(); val ff = (ft ushr 3).toInt(); val fw = (ft and 7).toInt()
                                if (ff == 4 && fw == 2) { val dl = f2.varint().toInt(); val from = f2.p; val to = minOf(from + dl, msg.size); readData = msg.copyOfRange(from, to); f2.p = to }
                                else skip(f2, fw)
                            }
                        } else skip(sub, sw)
                    }
                }
                else -> skip(r, wire)
            }
        }
        return RpcResp(cmdId, status, hasNext, readData, contentField, contentBytes, files)
    }

    // ---- System RPC (Settings): field numbers from flipper.proto ----
    // 5 ping, 31 reboot, 32 device_info_req/33 resp, 34 factory_reset,
    // 35 get_datetime_req/36 resp, 37 set_datetime_req, 38 play_alert,
    // 24 stat_req, 44 power_info_req/45 resp.
    fun statRequest(cmdId: Int, path: String): ByteArray {
        val inner = ArrayList<Byte>(); strField(inner, 1, path)
        return mainMsg(cmdId, false, 24, inner.toByteArray())
    }
    fun deviceInfoRequest(cmdId: Int): ByteArray = mainMsg(cmdId, false, 32, ByteArray(0))
    fun powerInfoRequest(cmdId: Int): ByteArray = mainMsg(cmdId, false, 44, ByteArray(0))
    fun getDateTimeRequest(cmdId: Int): ByteArray = mainMsg(cmdId, false, 35, ByteArray(0))
    fun playAlertRequest(cmdId: Int): ByteArray = mainMsg(cmdId, false, 38, ByteArray(0))
    fun factoryResetRequest(cmdId: Int): ByteArray = mainMsg(cmdId, false, 34, ByteArray(0))
    fun rebootRequest(cmdId: Int, mode: Int): ByteArray {
        val inner = ArrayList<Byte>(); tag(inner, 1, 0); varint(inner, mode.toLong())
        return mainMsg(cmdId, false, 31, inner.toByteArray())
    }
    /** SetDateTimeRequest{ DateTime datetime=1 { hour=1..weekday=7 } }. */
    fun setDateTimeRequest(cmdId: Int, hour: Int, minute: Int, second: Int, day: Int, month: Int, year: Int, weekday: Int): ByteArray {
        val dt = ArrayList<Byte>()
        tag(dt, 1, 0); varint(dt, hour.toLong()); tag(dt, 2, 0); varint(dt, minute.toLong())
        tag(dt, 3, 0); varint(dt, second.toLong()); tag(dt, 4, 0); varint(dt, day.toLong())
        tag(dt, 5, 0); varint(dt, month.toLong()); tag(dt, 6, 0); varint(dt, year.toLong())
        tag(dt, 7, 0); varint(dt, weekday.toLong())
        val inner = ArrayList<Byte>(); lenField(inner, 1, dt.toByteArray())
        return mainMsg(cmdId, false, 37, inner.toByteArray())
    }

    /** Parse a {key=1, value=2} body (device_info / power_info k/v). null if empty. */
    fun parseKeyValue(data: ByteArray): Pair<String, String>? {
        val r = Reader(data, 0, data.size); var key = ""; var value = ""
        while (!r.eof()) {
            val t = r.varint(); val f = (t ushr 3).toInt(); val w = (t and 7).toInt()
            if (w == 2 && (f == 1 || f == 2)) {
                val len = r.varint().toInt(); val from = r.p; val to = minOf(from + len, data.size); r.p = to
                val s = String(data, from, to - from, Charsets.UTF_8); if (f == 1) key = s else value = s
            } else skip(r, w)
        }
        return if (key.isEmpty() && value.isEmpty()) null else key to value
    }

    data class DateTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int)
    /** Parse get_datetime response (f36){ datetime=1 { hour=1,min=2,sec=3,day=4,month=5,year=6,weekday=7 } }. */
    fun parseDateTime(data: ByteArray): DateTime? {
        val r = Reader(data, 0, data.size)
        while (!r.eof()) {
            val t = r.varint(); val f = (t ushr 3).toInt(); val w = (t and 7).toInt()
            if (w == 2 && f == 1) {
                val len = r.varint().toInt(); val from = r.p; val to = minOf(from + len, data.size); r.p = to
                val d = Reader(data, from, to)
                var h = 0; var mi = 0; var s = 0; var day = 1; var mo = 1; var y = 2000
                while (!d.eof()) {
                    val dt = d.varint(); val df = (dt ushr 3).toInt(); val dw = (dt and 7).toInt()
                    if (dw == 0) { val v = d.varint().toInt(); when (df) { 1->h=v;2->mi=v;3->s=v;4->day=v;5->mo=v;6->y=v } } else skip(d, dw)
                }
                return DateTime(y, mo, day, h, mi, s)
            } else skip(r, w)
        }
        return null
    }

    // ---- decode ----

    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun eof() = p >= end
        fun u8() = b[p++].toInt() and 0xFF
        fun varint(): Long {
            var shift = 0; var res = 0L
            while (p < end && shift < 64) { // cap at 10 bytes — a malformed run can't spin or overflow shift
                val c = u8()
                res = res or ((c.toLong() and 0x7F) shl shift)
                if (c and 0x80 == 0) break
                shift += 7
            }
            return res
        }
    }

    /**
     * Parse one PB_Main. If it carries a gui_screen_frame (field 22) with a data blob
     * (sub-field 1), returns that framebuffer; otherwise null.
     */
    fun parseScreenFrame(msg: ByteArray): ByteArray? {
        val r = Reader(msg, 0, msg.size)
        while (!r.eof()) {
            val t = r.varint()
            val field = (t ushr 3).toInt(); val wire = (t and 7).toInt()
            when {
                field == 22 && wire == 2 -> {
                    val len = r.varint().toInt()
                    val sub = Reader(msg, r.p, minOf(r.p + len, msg.size))
                    r.p = sub.end
                    while (!sub.eof()) {
                        val st = sub.varint()
                        val sf = (st ushr 3).toInt(); val sw = (st and 7).toInt()
                        if (sf == 1 && sw == 2) {
                            val dl = sub.varint().toInt()
                            val from = sub.p; val to = minOf(from + dl, msg.size)
                            return msg.copyOfRange(from, to)
                        } else skip(sub, sw)
                    }
                }
                else -> skip(r, wire)
            }
        }
        return null
    }

    private fun skip(r: Reader, wire: Int) {
        when (wire) {
            0 -> r.varint()
            1 -> r.p += 8
            2 -> { val l = r.varint().toInt(); r.p += l }
            5 -> r.p += 4
            else -> r.p = r.end
        }
    }
}

/** Live mirror of the Flipper screen (128x64, 1bpp u8g2 page buffer). */
object ScreenState {
    var frame by mutableStateOf<ByteArray?>(null)      // raw 1024-byte framebuffer
    var frameCount by mutableIntStateOf(0)             // bumps each frame -> drives recomposition
    var streaming by mutableStateOf(false)

    fun onFrame(fb: ByteArray) {
        frame = fb
        frameCount++
    }
}
