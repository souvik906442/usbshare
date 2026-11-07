package com.example.usbshare

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** This phone is the USB host: talks to the other phone's accessory bulk endpoints. */
class HostTransport(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) : BufferedTransport() {

    override fun rawRead(buf: ByteArray): Int {
        var fast = 0
        while (!closed) {
            val t0 = System.nanoTime()
            val n = conn.bulkTransfer(epIn, buf, 0, buf.size, 1000)
            if (n > 0) return n
            if (n < 0) {
                // a timeout (idle link) takes ~1 s; failing instantly many times means the cable is gone
                if (System.nanoTime() - t0 < 200_000_000L) {
                    fast++
                    if (fast > 40) return -1
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        return -1
                    }
                } else {
                    fast = 0
                }
            }
        }
        return -1
    }

    override fun rawWrite(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val n = minOf(left, 16384)
            val r = conn.bulkTransfer(epOut, b, o, n, 5000)
            if (r < 0) throw IOException("USB write failed")
            o += r
            left -= r
        }
    }

    override fun onClose() {
        try {
            conn.releaseInterface(intf)
        } catch (e: Exception) {
        }
        try {
            conn.close()
        } catch (e: Exception) {
        }
    }
}

/** This phone is the accessory: the other phone (host) talks to us through a file descriptor. */
class AccessoryTransport(private val pfd: ParcelFileDescriptor) : BufferedTransport() {
    private val inp = FileInputStream(pfd.fileDescriptor)
    private val out = FileOutputStream(pfd.fileDescriptor)

    override fun rawRead(buf: ByteArray): Int {
        return try {
            inp.read(buf, 0, buf.size)
        } catch (e: IOException) {
            -1
        }
    }

    override fun rawWrite(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val n = minOf(left, 16384)
            out.write(b, o, n)
            o += n
            left -= n
        }
    }

    override fun onClose() {
        try {
            pfd.close()
        } catch (e: IOException) {
        }
    }
}
